package com.fiorino.cli.quant;

import com.fiorino.cli.ConsoleIO;
import com.fiorino.cli.quant.BtcQuantAnalyzer.AnalysisReport;
import com.fiorino.cli.quant.BtcQuantAnalyzer.SignalResult;

import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * ============================================================
 * BtcPredictionDashboard — BTC 量化分析結果 CLI 顯示器
 * ============================================================
 *
 * 負責將 BtcQuantAnalyzer 生成的分析報告渲染成美觀的 CLI 介面。
 * 提供互動式選單讓使用者可以重新分析或返回主選單。
 *
 * @author benson-code
 * @version 2.0.0
 */
public final class BtcPredictionDashboard {

    // ============================================================
    // ANSI 色彩碼
    // ============================================================

    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String DIM     = "\033[2m";
    private static final String RED     = "\033[31m";
    private static final String GREEN   = "\033[32m";
    private static final String YELLOW  = "\033[33m";
    private static final String BLUE    = "\033[34m";
    private static final String MAGENTA = "\033[35m";
    private static final String CYAN    = "\033[36m";
    private static final String WHITE   = "\033[37m";
    private static final String BRIGHT_WHITE = "\033[97m";

    private static final String ORANGE  = "\033[38;5;214m";
    private static final String GOLD    = "\033[38;5;220m";
    private static final String PURPLE  = "\033[38;5;135m";
    private static final String TEAL    = "\033[38;5;51m";
    private static final String LIME    = "\033[38;5;118m";
    private static final String PINK    = "\033[38;5;213m";

    private static final String BG_BLACK  = "\033[40m";
    private static final String BG_BLUE   = "\033[44m";
    private static final String BG_GREEN  = "\033[42m";
    private static final String BG_RED    = "\033[41m";
    private static final String BG_YELLOW = "\033[43m";

    private static final int WIDTH = 70;

    // ============================================================
    // 主入口
    // ============================================================

    /**
     * 運行量化分析模式的完整互動流程。
     *
     * @param apiKey CoinGlass API Key
     * @return true 表示返回主選單，false 表示退出程序
     */
    public static boolean run(String apiKey) {
        Scanner scanner = ConsoleIO.IN;

        while (true) {
            // 顯示加載中動畫
            showLoadingScreen();

            // 執行分析
            BtcQuantAnalyzer analyzer = new BtcQuantAnalyzer(apiKey);
            AnalysisReport report = analyzer.analyze();

            // 渲染結果
            renderReport(report);

            // 互動選單
            System.out.println();
            System.out.println(BLUE + "  " + "─".repeat(WIDTH - 2) + RESET);
            System.out.println();
            System.out.println("  " + BOLD + WHITE + "請選擇操作：" + RESET);
            System.out.println("  " + CYAN  + "[1]" + RESET + " 🔄 重新分析（刷新最新數據）");
            System.out.println("  " + YELLOW + "[2]" + RESET + " 📋 顯示詳細信號解讀");
            System.out.println("  " + MAGENTA + "[0]" + RESET + " ← 返回主選單");
            System.out.println();
            System.out.print(GOLD + BOLD + "  ❯ " + RESET + WHITE + "請輸入選項: " + RESET);

            String input = scanner.nextLine().trim();
            switch (input) {
                case "1" -> { /* 繼續循環，重新分析 */ }
                case "2" -> {
                    renderDetailedSignals(report);
                    System.out.println();
                    System.out.print(DIM + "  按 Enter 繼續..." + RESET);
                    scanner.nextLine();
                }
                case "0", "q", "Q" -> { return true; }
                default -> {
                    System.out.println(RED + "  無效選項" + RESET);
                    sleep(500);
                }
            }
        }
    }

    // ============================================================
    // 渲染方法
    // ============================================================

    private static void showLoadingScreen() {
        clearScreen();
        System.out.println();
        System.out.println(PURPLE + BOLD +
            "  ╔══════════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(PURPLE + BOLD +
            "  ║        🔮 BTC 量化分析 & 下月價格預測引擎 v2.0              ║" + RESET);
        System.out.println(PURPLE + BOLD +
            "  ║     Binance · alternative.me · Coinbase · CoinGecko        ║" + RESET);
        System.out.println(PURPLE + BOLD +
            "  ╚══════════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();
        System.out.println(DIM + "  正在從公開市場 API 抓取最新數據（Binance / alternative.me / Coinbase / CoinGecko）..." + RESET);
        System.out.println();

        String[] spinnerFrames = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        String[] tasks = {
            "  📡 連接公開市場數據端點...",
            "  😱 抓取恐懼貪婪指數（30日歷史）...",
            "  📊 分析未平倉量趨勢（OI）...",
            "  💰 計算資金費率加權均值...",
            "  ⚖️  分析散戶多空比歷史數據...",
            "  🐋 讀取大戶持倉多空比...",
            "  🏦 讀取機構資金流向（Coinbase Premium）...",
            "  💥 分析主動買賣盤壓力（Taker）...",
            "  🌐 計算 BTC 主導地位指標...",
            "  📈 由日 K 線計算歷史波動率...",
            "  🧮 執行加權評分模型...",
            "  🔮 生成下月價格預測區間..."
        };

        for (String task : tasks) {
            System.out.print(CYAN + spinnerFrames[0] + RESET + task);
            System.out.flush();
            sleep(200);
            System.out.println(GREEN + " ✓" + RESET);
        }
        System.out.println();
    }

    private static void renderReport(AnalysisReport report) {
        clearScreen();

        // ── 標題 ──
        System.out.println(PURPLE + BOLD +
            "  ╔══════════════════════════════════════════════════════════════╗" + RESET);
        System.out.println(PURPLE + BOLD +
            "  ║        🔮 BTC 量化預測報告  " + DIM + report.analysisTime() + PURPLE + BOLD + "        ║" + RESET);
        System.out.println(PURPLE + BOLD +
            "  ╚══════════════════════════════════════════════════════════════╝" + RESET);
        System.out.println();

        if (!report.hasFullData()) {
            System.out.println(YELLOW + "  ⚠  部分數據未能獲取（公開 API 暫時無回應或網路問題）" + RESET);
            System.out.println(DIM  + "     稍後重試，或檢查對外網路連線" + RESET);
            System.out.println();
        }

        // ── 當前價格 ──
        String priceStr = report.currentPrice() > 0
            ? String.format("$%,.0f USDT", report.currentPrice())
            : "N/A（無法獲取）";
        System.out.println("  " + BOLD + "當前 BTC 價格:  " + RESET + GOLD + BOLD + priceStr + RESET);

        // ── 分析目標月份 ──
        String targetMonthStr = report.targetMonth()
            .format(DateTimeFormatter.ofPattern("yyyy 年 MM 月"));
        System.out.println("  " + BOLD + "預測目標月份:  " + RESET + CYAN + targetMonthStr + RESET);
        System.out.println();

        // ── 綜合分數 ──
        renderCompositeScore(report.compositeScore(), report.marketSentiment());
        System.out.println();

        // ── 各信號摘要表格 ──
        renderSignalSummary(report);
        System.out.println();

        // ── 價格預測區間 ──
        renderPricePrediction(report);
        System.out.println();

        // ── 免責聲明 ──
        System.out.println(BLUE + "  " + "═".repeat(WIDTH - 2) + RESET);
        System.out.println(DIM + YELLOW +
            "  ⚠  本預測基於量化模型，僅供參考研究用途，不構成任何投資建議。" + RESET);
        System.out.println(DIM +
            "     加密貨幣市場極具波動性，實際價格可能大幅偏離預測區間。" + RESET);
        System.out.println(DIM +
            "     請在充分了解風險的基礎上自行判斷投資決策。" + RESET);
    }

    private static void renderCompositeScore(double score, String sentiment) {
        System.out.println(BLUE + "  " + "═".repeat(WIDTH - 2) + RESET);
        System.out.println(BOLD + "  📈 市場綜合評分" + RESET);
        System.out.println();

        // 確定顏色
        String scoreColor;
        if (score >= 50)       scoreColor = LIME;
        else if (score >= 20)  scoreColor = GREEN;
        else if (score >= -20) scoreColor = YELLOW;
        else if (score >= -50) scoreColor = ORANGE;
        else                   scoreColor = RED;

        // 大數字顯示分數
        System.out.println("  " + scoreColor + BOLD +
            String.format("  綜合分數: %+.1f / 100", score) + RESET +
            "  │  " + BOLD + sentiment + RESET);
        System.out.println();

        // 視覺化進度條（-100 到 +100，共 50 格）
        int barWidth = 50;
        int zeroPos = barWidth / 2;
        int scorePos = (int)((score + 100) / 200.0 * barWidth);
        scorePos = Math.max(0, Math.min(barWidth - 1, scorePos));

        StringBuilder bar = new StringBuilder("  [");
        for (int i = 0; i < barWidth; i++) {
            if (i == scorePos) {
                bar.append(scoreColor).append(BOLD).append("▼").append(RESET);
            } else if (i == zeroPos) {
                bar.append(DIM).append("|").append(RESET);
            } else if (i < zeroPos && i < scorePos) {
                bar.append(GREEN).append("█").append(RESET);
            } else if (i > zeroPos && i > scorePos) {
                bar.append(RED).append("█").append(RESET);
            } else {
                bar.append(DIM).append("░").append(RESET);
            }
        }
        bar.append("]");
        System.out.println("  極度看跌                     中性                      極度看漲");
        System.out.println(bar);
        System.out.println("  -100" + " ".repeat(21) + "0" + " ".repeat(21) + "+100");
    }

    private static void renderSignalSummary(AnalysisReport report) {
        System.out.println(BLUE + "  " + "═".repeat(WIDTH - 2) + RESET);
        System.out.println(BOLD + "  🔍 信號摘要（8 個市場維度）" + RESET);
        System.out.println();
        System.out.println(DIM + String.format("  %-22s %-10s %-8s %s", "信號名稱", "當前值", "分數", "趨勢判斷") + RESET);
        System.out.println("  " + "─".repeat(WIDTH - 4));

        for (SignalResult signal : report.signals()) {
            boolean isNA = signal.value().equals("N/A");

            String scoreColor;
            if (isNA) {
                scoreColor = DIM;
            } else if (signal.score() >= 40)  {
                scoreColor = LIME;
            } else if (signal.score() >= 10)  {
                scoreColor = GREEN;
            } else if (signal.score() >= -10) {
                scoreColor = YELLOW;
            } else if (signal.score() >= -40) {
                scoreColor = ORANGE;
            } else {
                scoreColor = RED;
            }

            String scoreStr = isNA ? " N/A" : String.format("%+.0f", signal.score());
            String valueStr = signal.value().length() > 14
                ? signal.value().substring(0, 13) + "…"
                : signal.value();

            System.out.printf("  %-22s " + CYAN + "%-14s" + RESET + " " +
                scoreColor + BOLD + "%-8s" + RESET + " %s%n",
                signal.name(), valueStr, scoreStr, scoreColor + signal.trend() + RESET);
        }
    }

    private static void renderPricePrediction(AnalysisReport report) {
        System.out.println(BLUE + "  " + "═".repeat(WIDTH - 2) + RESET);
        System.out.println(BOLD + "  🎯 下月 BTC 價格預測" + RESET +
            DIM + "  [" + report.targetMonth().format(DateTimeFormatter.ofPattern("yyyy-MM")) + "]" + RESET);
        System.out.println();

        if (report.currentPrice() <= 0) {
            System.out.println(YELLOW + "  ⚠  無法取得當前 BTC 價格，無法生成預測" + RESET);
            return;
        }

        double low  = report.predictedLow();
        double mid  = report.predictedMid();
        double high = report.predictedHigh();
        double cur  = report.currentPrice();

        double changeFromCurLow  = (low  - cur) / cur * 100;
        double changeFromCurMid  = (mid  - cur) / cur * 100;
        double changeFromCurHigh = (high - cur) / cur * 100;

        // 方向顏色
        String dirColor = report.direction().equals("BULLISH") ? GREEN :
                          report.direction().equals("BEARISH") ? RED : YELLOW;
        String dirLabel = report.direction().equals("BULLISH") ? "📈 看漲" :
                          report.direction().equals("BEARISH") ? "📉 看跌" : "↔ 中性";

        System.out.println("  " + BOLD + "市場方向：" + RESET + dirColor + BOLD + dirLabel + RESET);
        System.out.println("  " + BOLD + String.format("預測信心度：%s%.0f%%%s", CYAN, report.confidence(), RESET));
        System.out.println();

        // 三段式價格顯示
        System.out.println(String.format(
            "  " + RED    + BOLD + "悲觀估計: $%,.0f " + RESET + DIM + "(%+.1f%%)" + RESET,
            low, changeFromCurLow));
        System.out.println(String.format(
            "  " + YELLOW + BOLD + "基準預測: $%,.0f " + RESET + DIM + "(%+.1f%%)" + RESET,
            mid, changeFromCurMid));
        System.out.println(String.format(
            "  " + GREEN  + BOLD + "樂觀估計: $%,.0f " + RESET + DIM + "(%+.1f%%)" + RESET,
            high, changeFromCurHigh));
        System.out.println();

        // 視覺化價格條
        renderPriceBar(cur, low, mid, high);
    }

    private static void renderPriceBar(double cur, double low, double mid, double high) {
        int barWidth = 56;
        double minPrice = low * 0.97;
        double maxPrice = high * 1.03;
        double range = maxPrice - minPrice;

        int curPos  = (int)((cur  - minPrice) / range * barWidth);
        int lowPos  = (int)((low  - minPrice) / range * barWidth);
        int midPos  = (int)((mid  - minPrice) / range * barWidth);
        int highPos = (int)((high - minPrice) / range * barWidth);

        curPos  = Math.max(0, Math.min(barWidth - 1, curPos));
        lowPos  = Math.max(0, Math.min(barWidth - 1, lowPos));
        midPos  = Math.max(0, Math.min(barWidth - 1, midPos));
        highPos = Math.max(0, Math.min(barWidth - 1, highPos));

        // 渲染預測區間條
        System.out.print("  |");
        for (int i = 0; i < barWidth; i++) {
            if (i == curPos) {
                System.out.print(GOLD + BOLD + "◆" + RESET);  // 當前價格
            } else if (i == midPos) {
                System.out.print(YELLOW + BOLD + "│" + RESET); // 中位預測
            } else if (i >= lowPos && i <= highPos) {
                // 預測區間
                if (i < midPos) {
                    System.out.print(RED + "▒" + RESET);
                } else {
                    System.out.print(GREEN + "▒" + RESET);
                }
            } else {
                System.out.print(DIM + "─" + RESET);
            }
        }
        System.out.println("|");
        System.out.println(DIM + "  " + String.format("$%,.0f", minPrice) +
            " ".repeat(Math.max(0, barWidth - 13)) + String.format("$%,.0f", maxPrice) + RESET);
        System.out.println();
        System.out.println(DIM +
            "  " + GOLD + "◆" + DIM + " 當前價  " +
            RED + "▒" + DIM + " 悲觀區間  " +
            YELLOW + "│" + DIM + " 基準預測  " +
            GREEN + "▒" + DIM + " 樂觀區間" + RESET);
    }

    private static void renderDetailedSignals(AnalysisReport report) {
        clearScreen();
        System.out.println();
        System.out.println(PURPLE + BOLD + "  ═══ 詳細信號解讀報告 ═══" + RESET);
        System.out.println();

        for (SignalResult signal : report.signals()) {
            String scoreColor = signal.score() >= 20 ? GREEN : signal.score() <= -20 ? RED : YELLOW;

            System.out.println(BOLD + "  " + signal.name() + RESET +
                "  " + DIM + String.format("(權重 %.0f%%)", signal.weight() * 100) + RESET);
            System.out.println("  " + CYAN + "當前數值: " + RESET + signal.value());
            System.out.println("  " + scoreColor + "信號分數: " + String.format("%+.0f", signal.score()) + RESET +
                "  " + scoreColor + signal.trend() + RESET);
            System.out.println("  " + DIM + "📝 " + signal.interpretation() + RESET);
            System.out.println(BLUE + "  " + "─".repeat(WIDTH - 2) + RESET);
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private static void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
