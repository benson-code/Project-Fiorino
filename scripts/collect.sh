#!/bin/bash
# ================================================================
# Project Fiorino — Track A 每日量化數據採集
# ================================================================
# 由 launchd 排程觸發（見 com.fiorino.quant.collect.plist）。
# 也可手動執行：./scripts/collect.sh
# ================================================================
set -euo pipefail

# 專案根目錄（本腳本位於 <root>/scripts/）
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA="/opt/homebrew/opt/openjdk/bin/java"
JAR="$ROOT/target/project-fiorino-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
    echo "找不到 JAR，先建置：mvn clean package -DskipTests" >&2
    exit 1
fi

# H2 在 ./data 下，cd 到 ROOT 確保路徑一致
"$JAVA" -jar "$JAR" --collect
