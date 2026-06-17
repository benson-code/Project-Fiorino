package com.fiorino.cli.quant.research;

import com.fiorino.cli.ConsoleIO;
import java.util.Scanner;

/**
 * ============================================================
 * QuantResearchConsole — 量化研究控制台（互動選單）
 * ============================================================
 *
 * 主選單選項 3 進入。把原本只能用 headless 旗標跑的 Track A/B 工具
 * 包成互動選單，並清楚顯示所有路徑與目前狀態。
 *
 * 對應的 headless 旗標（給 launchd / 腳本用）：
 *   --collect / --backfill [天] / --features / --backtest
 */
public final class QuantResearchConsole {

    // ANSI
    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String DIM   = "\033[2m";
    private static final String LIME  = "\033[38;5;118m";
    private static final String CYAN  = "\033[0;36m";
    private static final String GOLD  = "\033[38;5;220m";
    private static final String WHITE = "\033[0;37m";
    private static final String RED   = "\033[0;31m";
    private static final String BLUE  = "\033[0;34m";

    private QuantResearchConsole() {}

    /** 進入互動控制台，直到使用者選擇返回。 */
    public static void run() {
        Scanner scanner = ConsoleIO.IN;
        while (true) {
            render();
            System.out.print(GOLD + BOLD + "  ❯ " + RESET + WHITE + "請輸入選項 [0-6]: " + RESET);
            String input = scanner.nextLine().trim();
            switch (input) {
                case "1" -> { pause("立即採集一次"); SnapshotCollector.runHeadless(); waitEnter(scanner); }
                case "2" -> {
                    System.out.print(WHITE + "  回填天數（直接 Enter = 365）: " + RESET);
                    String d = scanner.nextLine().trim();
                    int days = 365;
                    if (!d.isEmpty()) {
                        try { days = Integer.parseInt(d); }
                        catch (NumberFormatException e) { System.out.println(RED + "  ✗ 非數字，改用 365" + RESET); }
                    }
                    pause("回填最近 " + days + " 天歷史");
                    HistoricalBackfiller.runHeadless(days);
                    waitEnter(scanner);
                }
                case "3" -> { pause("建立特徵矩陣 + 匯出 CSV"); FeatureMatrixBuilder.runHeadless(); waitEnter(scanner); }
                case "4" -> { pause("回測（IC / 方向命中率）"); BacktestEngine.runHeadless(); waitEnter(scanner); }
                case "5" -> { showStatus(); waitEnter(scanner); }
                case "6" -> {
                    System.out.print(WHITE + "  分析間隔分鐘（直接 Enter = 15）: " + RESET);
                    String m = scanner.nextLine().trim();
                    int minutes = 15;
                    if (!m.isEmpty()) {
                        try { minutes = Integer.parseInt(m); }
                        catch (NumberFormatException e) { System.out.println(RED + "  ✗ 非數字，改用 15" + RESET); }
                    }
                    pause("即時連續預測（每 " + minutes + " 分鐘，q+Enter 停止）");
                    com.fiorino.cli.quant.LivePredictionRunner.run(minutes);
                    waitEnter(scanner);
                }
                case "0", "q", "Q" -> { return; }
                default -> { System.out.println(RED + "  ✗ 無效選項「" + input + "」" + RESET); sleep(500); }
            }
        }
    }

    // ============================================================
    // 渲染
    // ============================================================

    private static void render() {
        clear();
        System.out.println();
        System.out.println(LIME + BOLD + "  🧪 量化研究控制台  Quant Research Console" + RESET);
        System.out.println(BLUE + "  " + "═".repeat(63) + RESET);

        printPaths();

        System.out.println();
        System.out.println(BOLD + WHITE + "  請選擇操作" + RESET);
        System.out.println();
        opt("1", "📥 立即採集一次", "抓當下 8 信號 + 落地預測（= --collect）");
        opt("2", "📦 回填歷史", "拉 FNG/funding/klines + 重建溢價（= --backfill）");
        opt("3", "🧮 建特徵矩陣", "對齊未來 N 日報酬，匯出 CSV（= --features）");
        opt("4", "📊 回測 IC / 命中率", "衡量各信號預測力 + 區間覆蓋率（= --backtest）");
        opt("5", "📍 狀態 / 路徑", "看採集天數、最新預測、所有檔案位置");
        opt("6", "🔴 即時連續預測", "固定間隔反覆分析 + 盤中存檔（= --live [分鐘]）");
        System.out.println("  " + RED + BOLD + " 0 " + RESET + "  " + DIM + "← 返回主選單" + RESET);
        System.out.println();
        System.out.println(BLUE + "  " + "─".repeat(63) + RESET);
    }

    /** 路徑資訊（使用者要求「詳細寫好路徑」）。 */
    private static void printPaths() {
        String db = QuantDataStore.defaultDbPath();
        String runtime = System.getProperty("user.home") + "/.fiorino";
        System.out.println();
        System.out.println(DIM + "  路徑資訊：" + RESET);
        System.out.println("    " + CYAN + "H2 資料庫 " + RESET + ": " + db + ".mv.db");
        System.out.println("    " + CYAN + "採集器 jar" + RESET + ": " + runtime + "/project-fiorino.jar");
        System.out.println("    " + CYAN + "排程日誌 " + RESET + ": " + runtime + "/logs/quant-collect.log");
        System.out.println("    " + CYAN + "特徵 CSV " + RESET + ": " + runtime + "/feature_matrix.csv（執行選項 3 後產生）");
        System.out.println("    " + CYAN + "launchd  " + RESET + ": com.fiorino.quant.collect（每日 08:05）");
        System.out.println(DIM + "      檢查排程： launchctl print gui/$(id -u)/com.fiorino.quant.collect | grep -i state" + RESET);
        System.out.println(DIM + "      重新部署： ./scripts/deploy-collector.sh" + RESET);
    }

    private static void showStatus() {
        clear();
        System.out.println();
        System.out.println(LIME + BOLD + "  📍 量化研究狀態" + RESET);
        System.out.println(BLUE + "  " + "═".repeat(63) + RESET);
        printPaths();
        System.out.println();
        System.out.println(DIM + "  資料庫摘要：" + RESET);
        try (QuantDataStore store = new QuantDataStore()) {
            System.out.print(store.statusReport());
        } catch (Exception e) {
            System.out.println(RED + "  ⚠ 無法讀取資料庫: " + e.getMessage() + RESET);
        }
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static void opt(String key, String title, String desc) {
        System.out.println("  " + LIME + BOLD + " " + key + " " + RESET + "  " + BOLD + WHITE + title + RESET);
        System.out.println(DIM + "        " + desc + RESET);
    }

    private static void pause(String action) {
        clear();
        System.out.println();
        System.out.println(LIME + BOLD + "  ▶ " + action + " ..." + RESET);
        System.out.println(BLUE + "  " + "─".repeat(63) + RESET);
        System.out.println();
    }

    private static void waitEnter(Scanner scanner) {
        System.out.println();
        System.out.print(DIM + "  按 Enter 返回控制台..." + RESET);
        scanner.nextLine();
    }

    private static void clear() { System.out.print("\033[2J\033[H"); System.out.flush(); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
