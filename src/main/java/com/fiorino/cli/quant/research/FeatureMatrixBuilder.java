package com.fiorino.cli.quant.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ============================================================
 * FeatureMatrixBuilder — Track B2 特徵矩陣建構
 * ============================================================
 *
 * 從 H2 讀出每日（信號分數 + 綜合分 + 價格），對齊未來 N 日報酬，
 * 產出 {@link FeatureMatrix} 供 Track B3 回測。
 *
 * 重要：前瞻報酬只用「該觀測日之後」的價格 → 天然無未來函數
 * （predictor 用 D 當天，label 用 D+N，計算時兩者都已知，回測時不洩漏）。
 */
public final class FeatureMatrixBuilder {

    private static final Logger log = LoggerFactory.getLogger(FeatureMatrixBuilder.class);
    public static final int[] DEFAULT_HORIZONS = {1, 7, 14, 30};

    private final int[] horizons;

    public FeatureMatrixBuilder() { this(DEFAULT_HORIZONS); }
    public FeatureMatrixBuilder(int[] horizons) { this.horizons = horizons; }

    public FeatureMatrix build() {
        try (QuantDataStore store = new QuantDataStore()) {
            return build(store);
        }
    }

    public FeatureMatrix build(QuantDataStore store) {
        TreeMap<LocalDate, Double> prices = store.loadPriceSeries();
        TreeMap<LocalDate, Double> composites = store.loadComposites();
        TreeMap<LocalDate, LinkedHashMap<String, Double>> scores = store.loadSignalScores();

        // 蒐集所有出現過的信號 id（排序穩定）
        TreeSet<String> idSet = new TreeSet<>();
        scores.values().forEach(m -> idSet.addAll(m.keySet()));
        List<String> signalIds = new ArrayList<>(idSet);

        List<FeatureMatrix.Obs> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : prices.entrySet()) {
            LocalDate d = e.getKey();
            double priceD = e.getValue();
            if (priceD <= 0) continue;

            Map<String, Double> rowScores = scores.getOrDefault(d, new LinkedHashMap<>());
            double comp = composites.getOrDefault(d, Double.NaN);

            // 未來 N 日報酬
            LinkedHashMap<Integer, Double> fwd = new LinkedHashMap<>();
            for (int n : horizons) {
                Double pN = prices.get(d.plusDays(n));
                fwd.put(n, (pN != null && pN > 0) ? (pN - priceD) / priceD : Double.NaN);
            }
            rows.add(new FeatureMatrix.Obs(d, priceD, comp, rowScores, fwd));
        }

        FeatureMatrix fm = new FeatureMatrix(signalIds, horizons, rows);
        log.info("特徵矩陣建構完成 | {} 列 | {} 信號 | horizons={}",
            fm.size(), signalIds.size(), java.util.Arrays.toString(horizons));
        return fm;
    }

    // ============================================================
    // CLI 進入點：建矩陣 + 印摘要 + 匯出 CSV
    // ============================================================

    public static void runHeadless() {
        System.out.println("🧮 Project Fiorino — Track B2 特徵矩陣建構");
        FeatureMatrix fm = new FeatureMatrixBuilder().build();

        System.out.printf("   觀測列數: %d | 信號: %s%n", fm.size(), fm.signalIds());
        System.out.println("   各 horizon 可用樣本數（非 NaN 前瞻報酬）:");
        fm.coverage().forEach((n, c) ->
            System.out.printf("     ret_%-2d : %d 筆%n", n, c));

        Path out = Path.of("./data/feature_matrix.csv");
        try {
            Files.writeString(out, fm.toCsv());
            System.out.printf("✅ 已匯出 CSV → %s%n", out.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("⚠ CSV 匯出失敗: " + e.getMessage());
        }
        System.out.println("   下一步 B3 將用此矩陣計算各信號的 IC / 命中率 / 區間覆蓋率 / 夏普。");
    }
}
