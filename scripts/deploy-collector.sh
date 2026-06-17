#!/bin/bash
# ================================================================
# Project Fiorino — 部署 Track A 採集器到 ~/.fiorino 並掛上 launchd
# ================================================================
# 為什麼要部署到 ~/.fiorino：
#   launchd 排程行程對 ~/Documents（TCC 保護區）無存取權，會 exit 126。
#   把 jar / H2 資料 / 日誌都放到 ~/.fiorino（非保護區）即可繞開，無需 GUI。
#
# 用法（在專案根目錄，於有 Documents 存取權的 shell 執行）：
#   ./scripts/deploy-collector.sh
# 重新部署（改了程式後）：重跑本腳本即可。
# ================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$HOME/.fiorino"
JAR_SRC="$ROOT/target/project-fiorino-1.0.0-SNAPSHOT.jar"
JAR_DST="$RUNTIME/project-fiorino.jar"
PLIST="com.fiorino.quant.collect.plist"
AGENT="$HOME/Library/LaunchAgents/$PLIST"

echo "▶ 1/5 確認 JAR 已建置"
if [ ! -f "$JAR_SRC" ]; then
    echo "   找不到 JAR，建置中..."
    (cd "$ROOT" && mvn clean package -DskipTests -q)
fi

echo "▶ 2/5 建立 runtime 目錄 $RUNTIME"
mkdir -p "$RUNTIME/logs"

echo "▶ 3/5 部署 jar → $JAR_DST"
cp "$JAR_SRC" "$JAR_DST"

echo "▶ 4/5 遷移既有 H2 資料（若有且尚未遷移）"
OLD_DB="$ROOT/data/fiorino_quant.mv.db"
NEW_DB="$RUNTIME/fiorino_quant.mv.db"
if [ -f "$OLD_DB" ] && [ ! -f "$NEW_DB" ]; then
    cp "$OLD_DB" "$NEW_DB"
    echo "   已遷移 $OLD_DB → $NEW_DB"
else
    echo "   略過（無舊資料或目標已存在）"
fi

echo "▶ 5/5 安裝並載入 launchd agent"
cp "$ROOT/scripts/$PLIST" "$AGENT"
launchctl bootout "gui/$(id -u)/com.fiorino.quant.collect" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT"

echo ""
echo "✅ 部署完成。每日 08:05 自動採集，資料寫入 $RUNTIME/fiorino_quant"
echo "   立即測試： launchctl kickstart gui/$(id -u)/com.fiorino.quant.collect"
echo "   看日誌：   cat $RUNTIME/logs/quant-collect.log"
echo "   移除：     launchctl bootout gui/$(id -u)/com.fiorino.quant.collect"
