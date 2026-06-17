package com.fiorino.cli.quant.research;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * FeatureMatrix — Track B2 特徵矩陣
 * ============================================================
 *
 * 每個觀測日一列：信號分數（含 composite）對齊「未來 N 日報酬」。
 * 供 Track B3 回測引擎計算 IC / 命中率 / 區間覆蓋率 / 夏普。
 *
 * 前瞻報酬 ret_N = (price[D+N] − price[D]) / price[D]；
 * D+N 無價格（接近資料尾端）時為 Double.NaN，取對時自動排除。
 */
public final class FeatureMatrix {

    /** 單一觀測日。 */
    public record Obs(
        LocalDate date,
        double price,
        double composite,
        Map<String, Double> scores,        // signal_id → score（該日可得者）
        Map<Integer, Double> forwardReturns // N → ret_N（可能為 NaN）
    ) {}

    private final List<String> signalIds;   // 所有出現過的信號（排序後）
    private final int[] horizons;           // 前瞻天數，例如 {1,7,14,30}
    private final List<Obs> rows;

    public FeatureMatrix(List<String> signalIds, int[] horizons, List<Obs> rows) {
        this.signalIds = signalIds;
        this.horizons = horizons;
        this.rows = rows;
    }

    public List<String> signalIds() { return signalIds; }
    public int[] horizons()         { return horizons; }
    public List<Obs> rows()         { return rows; }
    public int size()               { return rows.size(); }

    /**
     * 取出「預測值 vs 未來報酬」的配對（自動排除任一為 NaN 者）。
     *
     * @param key       "composite" 或某個 signal_id
     * @param horizon   前瞻天數
     * @return List of double[]{predictor, forwardReturn}
     */
    public List<double[]> pairs(String key, int horizon) {
        List<double[]> out = new ArrayList<>();
        for (Obs o : rows) {
            double pred = "composite".equals(key)
                ? o.composite()
                : o.scores().getOrDefault(key, Double.NaN);
            double ret = o.forwardReturns().getOrDefault(horizon, Double.NaN);
            if (!Double.isNaN(pred) && !Double.isNaN(ret)) {
                out.add(new double[]{pred, ret});
            }
        }
        return out;
    }

    /** 匯出 CSV（給 Termius 上肉眼檢視）。欄位：date,price,composite,<signals>,ret_N... */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("date,price,composite");
        for (String s : signalIds) sb.append(',').append(s);
        for (int n : horizons) sb.append(",ret_").append(n);
        sb.append('\n');

        for (Obs o : rows) {
            sb.append(o.date()).append(',')
              .append(fmt(o.price())).append(',')
              .append(fmt(o.composite()));
            for (String s : signalIds) {
                Double v = o.scores().get(s);
                sb.append(',').append(v == null ? "" : fmt(v));
            }
            for (int n : horizons) {
                double r = o.forwardReturns().getOrDefault(n, Double.NaN);
                sb.append(',').append(Double.isNaN(r) ? "" : fmt(r));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format("%.6f", v);
    }

    /** 每個 horizon 有多少筆非 NaN 觀測（覆蓋度摘要）。 */
    public LinkedHashMap<Integer, Integer> coverage() {
        LinkedHashMap<Integer, Integer> cov = new LinkedHashMap<>();
        for (int n : horizons) cov.put(n, pairs("composite", n).size());
        return cov;
    }
}
