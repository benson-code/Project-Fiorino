#!/bin/bash
# ================================================================
# Project Fiorino — API Key 快速驗證工具
# 用於在啟動 Bot 前驗證 Testnet API Key 是否有效
# ================================================================
# 使用方式：
#   ./verify-api-key.sh API_KEY SECRET_KEY
# ================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

API_KEY="${1:-${FIORINO_API_KEY:-}}"
SECRET_KEY="${2:-${FIORINO_SECRET_KEY:-}}"
TESTNET_BASE="https://testnet.binance.vision"

echo -e "${BOLD}${CYAN}=== Project Fiorino — Testnet API Key 驗證 ===${NC}"
echo ""

if [ -z "$API_KEY" ] || [ -z "$SECRET_KEY" ]; then
    echo -e "${RED}❌ 使用方式: ./verify-api-key.sh <API_KEY> <SECRET_KEY>${NC}"
    echo -e "   或設定環境變數: export FIORINO_API_KEY=xxx && export FIORINO_SECRET_KEY=yyy"
    exit 1
fi

echo -e "  API Key (前8碼): ${CYAN}${API_KEY:0:8}...${NC}"
echo ""

# 步驟 1：Ping
echo -ne "  [1/5] Ping Testnet... "
PING_RESULT=$(curl -s --max-time 5 "${TESTNET_BASE}/api/v3/ping")
if [ "$PING_RESULT" = "{}" ]; then
    echo -e "${GREEN}✅ 正常${NC}"
else
    echo -e "${RED}❌ 失敗 - 請檢查網路${NC}"
    exit 1
fi

# 步驟 2：服務器時間
echo -ne "  [2/5] 服務器時間同步... "
SERVER_TIME=$(curl -s --max-time 5 "${TESTNET_BASE}/api/v3/time" | \
    python3 -c "import sys,json; print(json.load(sys.stdin)['serverTime'])" 2>/dev/null || echo "0")
LOCAL_TIME=$(($(date +%s) * 1000))
OFFSET=$((SERVER_TIME - LOCAL_TIME))
if [ "$SERVER_TIME" != "0" ]; then
    echo -e "${GREEN}✅ 偏差: ${OFFSET}ms${NC}"
    if [ "${OFFSET#-}" -gt 1000 ]; then
        echo -e "  ${YELLOW}⚠️  時鐘偏差超過 1 秒，可能影響簽名驗證${NC}"
    fi
else
    echo -e "${RED}❌ 無法獲取服務器時間${NC}"
    exit 1
fi

# 步驟 3：當前 BTC 價格
echo -ne "  [3/5] BTC/USDT 價格... "
BTC_PRICE=$(curl -s --max-time 5 "${TESTNET_BASE}/api/v3/ticker/price?symbol=BTCUSDT" | \
    python3 -c "import sys,json; print(float(json.load(sys.stdin)['price']))" 2>/dev/null || echo "0")
if [ "$BTC_PRICE" != "0" ]; then
    echo -e "${GREEN}✅ \$${BTC_PRICE} USDT${NC}"
else
    echo -e "${RED}❌ 無法獲取價格${NC}"
    exit 1
fi

# 步驟 4：帳戶認證
echo -ne "  [4/5] API Key 認證... "
TIMESTAMP=$(python3 -c "import time; print(int(time.time()*1000))")
QUERY="timestamp=${TIMESTAMP}&recvWindow=5000"
SIGNATURE=$(echo -n "$QUERY" | openssl dgst -sha256 -hmac "$SECRET_KEY" | sed 's/.*= //')

HTTP_RESP=$(curl -s --max-time 10 \
    -H "X-MBX-APIKEY: $API_KEY" \
    -w "\n%{http_code}" \
    "${TESTNET_BASE}/api/v3/account?${QUERY}&signature=${SIGNATURE}")

HTTP_BODY=$(echo "$HTTP_RESP" | head -1)
HTTP_CODE=$(echo "$HTTP_RESP" | tail -1)

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✅ 認證成功${NC}"
else
    echo -e "${RED}❌ 認證失敗 (HTTP $HTTP_CODE)${NC}"
    echo -e "  回應: ${HTTP_BODY}"
    echo ""
    echo -e "${YELLOW}可能原因：${NC}"
    echo "  • API Key 不是 Testnet 的（Testnet 和主網 Key 不通用）"
    echo "  • API Key 已失效（Testnet Key 定期重置）"
    echo "  • Secret Key 複製不完整"
    exit 1
fi

# 步驟 5：帳戶餘額
echo -ne "  [5/5] 讀取帳戶餘額... "
BTC_FREE=$(echo "$HTTP_BODY" | python3 -c "
import sys, json
data = json.load(sys.stdin)
balances = {b['asset']: float(b['free']) for b in data.get('balances', []) if b['asset'] in ['BTC','USDT']}
btc = balances.get('BTC', 0)
usdt = balances.get('USDT', 0)
print(f'BTC:{btc:.6f} USDT:{usdt:.2f}')
" 2>/dev/null || echo "BTC:0 USDT:0")
echo -e "${GREEN}✅${NC} | $BTC_FREE"

echo ""
echo -e "${BOLD}${GREEN}═══════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}✅ 所有驗證通過！可以啟動 Bot${NC}"
echo -e "${BOLD}${GREEN}═══════════════════════════════════════${NC}"
echo ""
echo -e "當前 Testnet BTC 價格: ${BOLD}\$${BTC_PRICE} USDT${NC}"
echo ""

# 自動計算建議網格範圍（當前價格 ±15%）
LOWER=$(python3 -c "p=${BTC_PRICE}; import math; l=round(p*0.85/500)*500; print(int(l))")
UPPER=$(python3 -c "p=${BTC_PRICE}; import math; u=round(p*1.15/500)*500; print(int(u))")

echo -e "${BOLD}建議網格參數（基於當前價格自動計算）：${NC}"
echo -e "  FIORINO_LOWER_PRICE=${LOWER}   # 當前價 -15%"
echo -e "  FIORINO_UPPER_PRICE=${UPPER}   # 當前價 +15%"
echo -e "  FIORINO_GRID_COUNT=10          # 10 格，間距 $(python3 -c "print(($UPPER-$LOWER)//10)") USDT"
echo -e "  FIORINO_INVESTMENT=100         # 100 USDT Testnet 模擬資金"
echo ""
echo -e "${CYAN}啟動命令：${NC}"
echo ""
echo "  FIORINO_API_KEY='${API_KEY}' \\"
echo "  FIORINO_SECRET_KEY='${SECRET_KEY}' \\"
echo "  FIORINO_TESTNET=true \\"
echo "  FIORINO_LOWER_PRICE=${LOWER} \\"
echo "  FIORINO_UPPER_PRICE=${UPPER} \\"
echo "  FIORINO_GRID_COUNT=10 \\"
echo "  FIORINO_INVESTMENT=100 \\"
echo "  java -Xms64m -Xmx256m -XX:+UseZGC -XX:+ZGenerational \\"
echo "       -jar target/project-fiorino-1.0.0-SNAPSHOT.jar"
echo ""
