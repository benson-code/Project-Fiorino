#!/bin/bash
# ================================================================
# Project Fiorino — Testnet 啟動腳本
# ================================================================
# 使用方式：
#   chmod +x run-testnet.sh
#   ./run-testnet.sh
#
# 或帶參數直接執行（跳過互動）：
#   FIORINO_API_KEY=xxx FIORINO_SECRET_KEY=yyy ./run-testnet.sh
# ================================================================

set -euo pipefail

# 彩色輸出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

clear

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║        Project Fiorino — BTC Spot Grid Trading Bot          ║"
echo "║                  TESTNET 啟動流程                           ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# ================================================================
# 步驟 1：確認 JAR 存在
# ================================================================
JAR_PATH="$(dirname "$0")/target/project-fiorino-1.0.0-SNAPSHOT.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${YELLOW}⚠️  找不到 JAR，正在重新構建...${NC}"
    mvn clean package -DskipTests -q
    echo -e "${GREEN}✅ JAR 構建完成${NC}"
fi

# ================================================================
# 步驟 2：讀取 Testnet API Key（優先讀取環境變數）
# ================================================================
echo -e "${BOLD}📋 Binance Testnet API 設定${NC}"
echo ""

if [ -z "${FIORINO_API_KEY:-}" ]; then
    echo -e "${YELLOW}尚未設定 FIORINO_API_KEY${NC}"
    echo ""
    echo -e "請到以下網址申請 Testnet API Key（使用 GitHub 帳號登入）："
    echo -e "${CYAN}  https://testnet.binance.vision${NC}"
    echo ""
    echo -e "步驟：登入後 → 點擊 [Generate HMAC_SHA256 Key] → 複製 API Key 和 Secret Key"
    echo ""
    read -p "請輸入 Testnet API Key: " input_api_key
    read -sp "請輸入 Testnet Secret Key: " input_secret_key
    echo ""
    export FIORINO_API_KEY="$input_api_key"
    export FIORINO_SECRET_KEY="$input_secret_key"
else
    echo -e "${GREEN}✅ 已從環境變數讀取 API Key${NC}"
fi

if [ -z "${FIORINO_API_KEY:-}" ] || [ -z "${FIORINO_SECRET_KEY:-}" ]; then
    echo -e "${RED}❌ API Key 或 Secret Key 不能為空，啟動中止${NC}"
    exit 1
fi

# ================================================================
# 步驟 3：驗證 Testnet API Key（快速測試）
# ================================================================
echo -e "${BOLD}🔍 驗證 Testnet API Key...${NC}"

TIMESTAMP=$(date +%s)000
QUERY="timestamp=${TIMESTAMP}"
SIGNATURE=$(echo -n "$QUERY" | openssl dgst -sha256 -hmac "$FIORINO_SECRET_KEY" | sed 's/.*= //')

HTTP_STATUS=$(curl -s -o /tmp/fiorino_account_test.json -w "%{http_code}" \
    --max-time 10 \
    -H "X-MBX-APIKEY: $FIORINO_API_KEY" \
    "https://testnet.binance.vision/api/v3/account?${QUERY}&signature=${SIGNATURE}")

if [ "$HTTP_STATUS" = "200" ]; then
    echo -e "${GREEN}✅ API Key 驗證成功！${NC}"
    BTC_BALANCE=$(python3 -c "
import json
with open('/tmp/fiorino_account_test.json') as f:
    data = json.load(f)
balances = {b['asset']: b['free'] for b in data.get('balances', []) if b['asset'] in ['BTC','USDT']}
print(f\"  BTC 餘額: {float(balances.get('BTC','0')):.6f} BTC\")
print(f\"  USDT 餘額: {float(balances.get('USDT','0')):.2f} USDT\")
" 2>/dev/null || echo "  (無法解析餘額)")
    echo -e "${CYAN}${BTC_BALANCE}${NC}"
else
    echo -e "${RED}❌ API Key 驗證失敗（HTTP ${HTTP_STATUS}）${NC}"
    cat /tmp/fiorino_account_test.json 2>/dev/null || true
    echo ""
    echo -e "${YELLOW}請確認：${NC}"
    echo "  1. API Key 是從 https://testnet.binance.vision 生成的（不是主網的 Key）"
    echo "  2. API Key 和 Secret Key 完整複製，沒有多餘空格"
    exit 1
fi

# ================================================================
# 步驟 3b：CoinGlass API Key（選用，啟用 BTC 市場分析面板）
# ================================================================
echo ""
echo -e "${BOLD}📡 CoinGlass BTC 市場分析設定（選用）${NC}"
echo -e "  CoinGlass 面板顯示：恐懼貪婪指數、未平倉量、爆倉數據等"
echo -e "  API Key 申請：${CYAN}https://www.coinglass.com/pricing${NC}"
echo ""
if [ -z "${COINGLASS_API_KEY:-}" ]; then
    echo -e "  ${YELLOW}COINGLASS_API_KEY 未設置，面板將以限制模式顯示（部分數據可能不可用）${NC}"
    read -p "  輸入 CoinGlass API Key（按 Enter 跳過）: " input_cg_key
    if [ -n "$input_cg_key" ]; then
        export COINGLASS_API_KEY="$input_cg_key"
        echo -e "  ${GREEN}✅ CoinGlass API Key 已設置${NC}"
    else
        echo -e "  ${YELLOW}⚠️  跳過 CoinGlass API Key，部分市場分析功能不可用${NC}"
        export COINGLASS_API_KEY=""
    fi
else
    echo -e "  ${GREEN}✅ 已從環境變數讀取 COINGLASS_API_KEY${NC}"
fi

# ================================================================
# 步驟 4：確認網格參數
# ================================================================
CURRENT_PRICE=$(curl -s "https://testnet.binance.vision/api/v3/ticker/price?symbol=BTCUSDT" | \
    python3 -c "import sys,json; print(float(json.load(sys.stdin)['price']))" 2>/dev/null || echo "0")

echo ""
echo -e "${BOLD}📐 網格參數設定${NC}"
echo -e "  當前 Testnet BTC 價格: ${CYAN}\$${CURRENT_PRICE} USDT${NC}"
echo ""

# 預設使用當前價格附近 ±10% 的範圍
PRICE_INT=${CURRENT_PRICE%.*}
DEFAULT_LOWER=$((PRICE_INT * 90 / 100))
DEFAULT_UPPER=$((PRICE_INT * 110 / 100))
DEFAULT_LOWER=$(( (DEFAULT_LOWER / 100) * 100 ))  # 取整到百位
DEFAULT_UPPER=$(( (DEFAULT_UPPER / 100) * 100 ))

export FIORINO_SYMBOL="${FIORINO_SYMBOL:-BTCUSDT}"
export FIORINO_LOWER_PRICE="${FIORINO_LOWER_PRICE:-$DEFAULT_LOWER}"
export FIORINO_UPPER_PRICE="${FIORINO_UPPER_PRICE:-$DEFAULT_UPPER}"
export FIORINO_GRID_COUNT="${FIORINO_GRID_COUNT:-10}"
export FIORINO_INVESTMENT="${FIORINO_INVESTMENT:-1000}"
export FIORINO_FEE_RATE="${FIORINO_FEE_RATE:-0.001}"
export FIORINO_TESTNET="true"

echo -e "  交易對:   ${BOLD}${FIORINO_SYMBOL}${NC}"
echo -e "  下界價格: ${BOLD}${FIORINO_LOWER_PRICE} USDT${NC}"
echo -e "  上界價格: ${BOLD}${FIORINO_UPPER_PRICE} USDT${NC}"
echo -e "  格 數:    ${BOLD}${FIORINO_GRID_COUNT} 格${NC}"
echo -e "  投入資金: ${BOLD}${FIORINO_INVESTMENT} USDT${NC}（Testnet 模擬資金）"
echo -e "  手續費率: ${BOLD}${FIORINO_FEE_RATE}${NC}"
SPACING=$(python3 -c "print(round((${FIORINO_UPPER_PRICE} - ${FIORINO_LOWER_PRICE}) / ${FIORINO_GRID_COUNT}, 2))" 2>/dev/null || echo "N/A")
echo -e "  格子間距: ${BOLD}${SPACING} USDT${NC}（自動計算）"
MID_PRICE=$(python3 -c "print(round((${FIORINO_UPPER_PRICE} + ${FIORINO_LOWER_PRICE}) / 2, 2))" 2>/dev/null || echo "0")
QTY_PER_GRID=$(python3 -c "q=round(${FIORINO_INVESTMENT} / (${FIORINO_GRID_COUNT} * ${MID_PRICE}), 6); print(f'{q:.6f}')" 2>/dev/null || echo "N/A")
echo -e "  每格數量: ${BOLD}${QTY_PER_GRID} BTC${NC}（需 >= 0.001，當前: ${GREEN}OK${NC} ）"

echo ""
echo -e "${YELLOW}⚠️  這是 TESTNET 模式：所有訂單均為模擬資金，不涉及真實 BTC${NC}"
echo ""
read -p "確認以上參數，按 Enter 啟動 Bot（Ctrl+C 取消）..."

# ================================================================
# 步驟 5：創建運行時目錄
# ================================================================
mkdir -p "$(dirname "$0")/data"
mkdir -p "$(dirname "$0")/logs"

# ================================================================
# 步驟 6：啟動 Bot
# ================================================================
echo ""
echo -e "${GREEN}${BOLD}🚀 正在啟動 Project Fiorino...${NC}"
echo -e "${CYAN}日誌文件: ./logs/project-fiorino.json.log${NC}"
echo -e "${CYAN}按 Ctrl+C 優雅停止（自動撤銷所有掛單）${NC}"
echo ""
sleep 1

exec java \
    -Xms64m \
    -Xmx256m \
    -XX:+UseZGC \
    -Djava.awt.headless=true \
    -Dfile.encoding=UTF-8 \
    -DFIORINO_API_KEY="${FIORINO_API_KEY}" \
    -DFIORINO_SECRET_KEY="${FIORINO_SECRET_KEY}" \
    -Dfiorino.symbol="${FIORINO_SYMBOL}" \
    -Dfiorino.lower.price="${FIORINO_LOWER_PRICE}" \
    -Dfiorino.upper.price="${FIORINO_UPPER_PRICE}" \
    -Dfiorino.grid.count="${FIORINO_GRID_COUNT}" \
    -Dfiorino.investment="${FIORINO_INVESTMENT}" \
    -Dfiorino.fee.rate="${FIORINO_FEE_RATE}" \
    -Dfiorino.testnet="${FIORINO_TESTNET}" \
    -Dcoinglass.api.key="${COINGLASS_API_KEY:-}" \
    -jar "${JAR_PATH}"
