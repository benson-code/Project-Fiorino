package com.fiorino.cli.quant.research;

import com.fiorino.cli.quant.BtcQuantAnalyzer;
import com.fiorino.cli.quant.BtcQuantAnalyzer.AnalysisReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * ============================================================
 * SnapshotCollector — Track A 前向採集器
 * ============================================================
 *
 * headless（無互動 UI）跑一次 BTC 量化分析，把 8 個信號分數 + 價格 + 綜合分數
 * 寫入 H2，並記錄該次預測供 Track C 事後評分。
 *
 * 設計給排程使用（Mac mini launchd 每日觸發 `java -jar ... --collect`）：
 *   - 唯一目的就是「把今天的衍生品微結構信號存下來」，因為 futures/data/*
 *     免費只給 30 天，每拖一天就永久遺失（見 QUANT_BACKTEST_PLAN.md §0）。
 *   - 全程不需要任何 API Key。
 */
public final class SnapshotCollector {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCollector.class);

    private SnapshotCollector() {}

    /** 執行一次採集，回傳本次寫入後的總筆數。 */
    public static int collectOnce() {
        log.info("Track A 採集器啟動...");
        BtcQuantAnalyzer analyzer = new BtcQuantAnalyzer("");  // 公開 API，免 Key
        AnalysisReport report = analyzer.analyze();

        try (QuantDataStore store = new QuantDataStore()) {
            Instant now = Instant.now();
            store.saveSnapshot(now, "LIVE", report);
            store.logPrediction(now, report);
            int total = store.snapshotCount();
            log.info("Track A 採集完成 | 綜合分數={} | 已累積 {} 筆觀測",
                String.format("%.2f", report.compositeScore()), total);
            return total;
        }
    }

    /** 給 --collect 進入點：印出精簡結果並回傳累積筆數。 */
    public static void runHeadless() {
        System.out.println("📥 Project Fiorino — Track A 量化數據採集");
        int total = collectOnce();
        System.out.printf("✅ 採集完成，資料庫已累積 %d 筆每日觀測（%s）%n", total, QuantDataStore.defaultDbPath());
        System.out.println("   提示：衍生品信號免費僅 30 天歷史，請維持每日排程採集以累積回測樣本。");
    }
}
