package com.fiorino.cli.quant;

import com.fiorino.cli.quant.BtcQuantAnalyzer.AnalysisReport;
import com.fiorino.cli.quant.BtcQuantAnalyzer.SignalResult;
import com.fiorino.cli.quant.research.QuantDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================
 * LivePredictionRunner — 即時連續預測模式（--live [間隔分鐘]）
 * ============================================================
 *
 * 以固定間隔反覆執行 8 信號量化分析 + 下月預測，全程免 API Key：
 *   - 每輪渲染一個精簡儀表板 frame（價格 / 綜合分 / 預測區間 / 各信號）
 *   - 每輪觀測以 source=INTRADAY、分鐘級 ts 寫入 H2（原始數據累積）
 *   - 顯示與上一輪的綜合分變化（Δ），便於盯盤期間觀察信號遷移
 *
 * 與每日管線的隔離（重要）：
 *   - INTRADAY 觀測不進入回測的日線樣本（QuantDataStore 載入器只讀 LIVE/BACKFILL）
 *     —— 盤中高頻樣本高度重疊，對「下月預測準確度」沒有額外統計資訊。
 *   - 不寫 quant_prediction：官方每日預測仍由 launchd 08:05 的 --collect 落地，
 *     確保 Track C 真實 track record 維持乾淨的每日一筆。
 *
 * 停止方式：Ctrl+C，或互動模式下輸入 q + Enter。
 */
public final class LivePredictionRunner {

    private static final Logger log = LoggerFactory.getLogger(LivePredictionRunner.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ANSI（與其他 CLI 畫面一致的配色）
    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String DIM   = "\033[2m";
    private static final String LIME  = "\033[38;5;118m";
    private static final String CYAN  = "\033[0;36m";
    private static final String GOLD  = "\033[38;5;220m";
    private static final String RED   = "\033[0;31m";
    private static final String BLUE  = "\033[0;34m";

    private LivePredictionRunner() {}

    /**
     * 進入連續預測迴圈，直到 Ctrl+C 或（互動模式）q + Enter。
     *
     * @param intervalMinutes 兩輪分析的間隔分鐘數（最小 1；免費 API 限額下 1 分鐘也安全，
     *                        每輪僅 ~10 個 HTTP 請求，其中 CoinGecko 1 個）
     */
    public static void run(int intervalMinutes) {
        int interval = Math.max(1, intervalMinutes);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread mainThread = Thread.currentThread();
        startStopWatcher(running, mainThread);

        BtcQuantAnalyzer analyzer = new BtcQuantAnalyzer("");  // 公開 API，免 Key
        boolean interactive = System.console() != null;
        double prevComposite = Double.NaN;
        int round = 0;

        System.out.printf("🔴 Project Fiorino — 即時連續預測模式（每 %d 分鐘一輪，Ctrl+C 或 q+Enter 停止）%n", interval);
        while (running.get()) {
            round++;
            AnalysisReport report;
            try {
                report = analyzer.analyze();
            } catch (Exception e) {
                log.warn("本輪分析失敗，{} 分鐘後重試: {}", interval, e.getMessage());
                if (!sleepMinutes(interval, running)) break;
                continue;
            }

            int totalIntraday = persistIntraday(report);
            if (interactive) { System.out.print("\033[2J\033[H"); System.out.flush(); }
            renderFrame(report, round, interval, prevComposite, totalIntraday);
            prevComposite = report.compositeScore();

            if (!sleepMinutes(interval, running)) break;
        }
        System.out.println();
        System.out.println("⏹ 即時預測模式已停止。");
    }

    // ============================================================
    // 渲染
    // ============================================================

    private static void renderFrame(AnalysisReport r, int round, int interval,
                                    double prevComposite, int totalIntraday) {
        String now = LocalDateTime.now().format(TIME_FMT);
        System.out.println();
        System.out.printf("%s%s🔴 LIVE 即時量化預測%s  %s#%d%s  %s  %s（間隔 %d 分）%s%n",
            LIME, BOLD, RESET, GOLD, round, RESET, now, DIM, interval, RESET);
        System.out.println(BLUE + "  " + "═".repeat(63) + RESET);

        String delta = Double.isNaN(prevComposite) ? ""
            : String.format("  %sΔ%+.1f vs 上輪%s", DIM, r.compositeScore() - prevComposite, RESET);
        System.out.printf("  %sBTC $%,.0f%s   綜合分 %s%+.1f%s（%s）%s%n",
            BOLD, r.currentPrice(), RESET,
            r.compositeScore() >= 0 ? LIME : RED, r.compositeScore(), RESET,
            r.marketSentiment(), delta);
        System.out.printf("  下月預測   %s$%,.0f%s ── %s$%,.0f%s ── %s$%,.0f%s   信心 %.0f%%%s（啟發式分數，非命中機率）%s%n",
            RED, r.predictedLow(), RESET, GOLD + BOLD, r.predictedMid(), RESET,
            LIME, r.predictedHigh(), RESET, r.confidence(), DIM, RESET);

        System.out.println(BLUE + "  " + "─".repeat(63) + RESET);
        for (SignalResult s : r.signals()) {
            String color = "N/A".equals(s.value()) ? DIM : s.score() > 10 ? LIME : s.score() < -10 ? RED : "";
            System.out.printf("  %-18s %s%+6.0f%s  %-14s %s%s%s%n",
                s.name(), color, s.score(), RESET, s.trend(), DIM, s.value(), RESET);
        }
        System.out.println(BLUE + "  " + "─".repeat(63) + RESET);
        System.out.printf("  %s已存 INTRADAY 觀測 %d 筆 → %s（不污染日線回測樣本）%s%n",
            DIM, totalIntraday, QuantDataStore.defaultDbPath(), RESET);
        System.out.printf("  %s⚠ 盤中樣本高度重疊：精準度評估以 --backtest 歷史覆蓋率與 Track C 到期驗證為準%s%n",
            DIM, RESET);
    }

    // ============================================================
    // 持久化 / 停止 / 等待
    // ============================================================

    /** 寫入本輪 INTRADAY 觀測；失敗不中斷迴圈（顯示仍有價值）。回傳累積筆數，失敗回 -1。 */
    private static int persistIntraday(AnalysisReport report) {
        try (QuantDataStore store = new QuantDataStore()) {
            store.saveIntraday(Instant.now(), report);
            return store.snapshotCount();
        } catch (Exception e) {
            log.warn("INTRADAY 寫入失敗（迴圈繼續）: {}", e.getMessage());
            return -1;
        }
    }

    /** 互動模式下監看 stdin：q / quit / exit + Enter 停止。headless（stdin 關閉）時執行緒安靜退出。 */
    private static void startStopWatcher(AtomicBoolean running, Thread mainThread) {
        Thread.ofVirtual().name("live-stop-watcher").start(() -> {
            try {
                watch: while (true) {
                    String line = com.fiorino.cli.ConsoleIO.IN.nextLine().trim().toLowerCase();
                    switch (line) {
                        case "q", "quit", "exit" -> { break watch; }
                        default -> { /* 其他輸入忽略，繼續監看 */ }
                    }
                }
                running.set(false);
                mainThread.interrupt();
            } catch (Exception ignored) {
                // stdin 不可用（nohup / launchd），停止方式只剩 Ctrl+C / SIGTERM
            }
        });
    }

    /** 分段睡眠以便及時響應停止；被中斷或 running=false 時回傳 false。 */
    private static boolean sleepMinutes(int minutes, AtomicBoolean running) {
        long until = System.currentTimeMillis() + minutes * 60_000L;
        while (System.currentTimeMillis() < until) {
            if (!running.get()) return false;
            try {
                Thread.sleep(Math.max(0, Math.min(1_000, until - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                return false;
            }
        }
        return running.get();
    }
}
