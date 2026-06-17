package com.fiorino.cli.quant.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ============================================================
 * BacktestEngine — Track B3 回測引擎
 * ============================================================
 *
 * 用 {@link FeatureMatrix} 衡量每個信號（及綜合分）對未來報酬的預測力：
 *   - IC（資訊係數）：predictor 與 未來 N 日報酬的 Spearman 秩相關
 *   - 方向命中率：sign(predictor) == sign(forwardReturn) 的比例
 *
 * 目前資料現實（見 QUANT_BACKTEST_PLAN.md）：
 *   只有 fear_greed / funding_rate / coinbase_premium 有長歷史，
 *   其餘 5 個衍生品信號要等 Track A 累積。本引擎對「可得樣本」運算，
 *   隨數據增厚自動涵蓋更多信號 —— 同一支程式，無需改寫。
 *
 * ⚠️ 統計警語（誠實揭露，非裝飾）：
 *   1. 多日 horizon 的每日滾動樣本高度重疊 → t 檢定會虛高，IC 僅供方向參考。
 *   2. 365 天 ≈ 單一市場格局，不能外推到牛熊轉換。
 *   3. 零變異信號（如當前 funding）IC 無定義，標記為「—」。
 */
public final class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    /** predictor 樣本內標準差低於此值即視為「零變異」（資訊量趨近於零）。 */
    static final double ZERO_VAR_EPS = 1e-9;

    public record IcRow(String key, int n, double[] icByHorizon, double[] hitByHorizon, double predictorStd) {
        /** 樣本內 predictor 幾乎無波動 → IC 無定義、當前不貢獻預測力（Track B4 校準時應降權）。 */
        public boolean isZeroVariance() { return predictorStd < ZERO_VAR_EPS; }
    }

    private final FeatureMatrix fm;

    public BacktestEngine(FeatureMatrix fm) { this.fm = fm; }

    /** 對 composite + 每個信號，算各 horizon 的 IC 與命中率。 */
    public java.util.List<IcRow> run() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add("composite");
        keys.addAll(fm.signalIds());

        java.util.List<IcRow> rows = new java.util.ArrayList<>();
        int[] hs = fm.horizons();
        for (String key : keys) {
            double[] ic = new double[hs.length];
            double[] hit = new double[hs.length];
            int nAtFirst = 0;
            double predictorStd = Double.NaN;
            for (int i = 0; i < hs.length; i++) {
                List<double[]> pairs = fm.pairs(key, hs[i]);
                if (i == 0) { nAtFirst = pairs.size(); predictorStd = predictorStdev(pairs); }
                ic[i] = spearman(pairs);
                hit[i] = directionalHit(pairs);
            }
            rows.add(new IcRow(key, nAtFirst, ic, hit, predictorStd));
        }
        return rows;
    }

    // ============================================================
    // 統計
    // ============================================================

    /** Spearman 秩相關（含平均秩處理 ties）；樣本不足或零變異回傳 NaN。 */
    static double spearman(List<double[]> pairs) {
        int n = pairs.size();
        if (n < 5) return Double.NaN;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = pairs.get(i)[0]; y[i] = pairs.get(i)[1]; }
        return pearson(rank(x), rank(y));
    }

    /** predictor 欄（pairs[i][0]）的母體標準差；空樣本回傳 NaN。用於偵測零變異信號。 */
    static double predictorStdev(List<double[]> pairs) {
        int n = pairs.size();
        if (n == 0) return Double.NaN;
        double mean = 0; for (double[] p : pairs) mean += p[0]; mean /= n;
        double var = 0; for (double[] p : pairs) { double d = p[0] - mean; var += d * d; } var /= n;
        return Math.sqrt(var);
    }

    /** 方向命中率：sign(predictor)==sign(return) 的比例（predictor 為 0 不計）。 */
    static double directionalHit(List<double[]> pairs) {
        int hit = 0, tot = 0;
        for (double[] p : pairs) {
            if (p[0] == 0) continue;
            tot++;
            if ((p[0] > 0 && p[1] > 0) || (p[0] < 0 && p[1] < 0)) hit++;
        }
        return tot == 0 ? Double.NaN : (double) hit / tot;
    }

    /** 平均秩（ties 取平均），用於 Spearman。 */
    private static double[] rank(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++;
            double avgRank = (i + j) / 2.0 + 1;     // 1-based 平均秩
            for (int k = i; k <= j; k++) r[idx[k]] = avgRank;
            i = j + 1;
        }
        return r;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx, dy = y[i] - my;
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) return Double.NaN;   // 零變異
        return sxy / Math.sqrt(sxx * syy);
    }

    static String interpretIc(double ic) {
        if (Double.isNaN(ic)) return "—";
        double a = Math.abs(ic);
        String mag = a < 0.05 ? "可忽略" : a < 0.10 ? "微弱" : a < 0.20 ? "中等" : "顯著";
        return String.format("%+.3f(%s)", ic, mag);
    }

    // ============================================================
    // 策略回測（用「次日報酬」→ 樣本不重疊，夏普乾淨）
    // ============================================================

    public record StrategyResult(
        int days, double totalReturn, double annSharpe, double maxDrawdown,
        double winRate, int flips, double buyHoldReturn) {}

    /**
     * 以 predictor 的「方向」每日做多/做空/空手，持有到次日。
     * deadzone 內視為空手（降低換手）；每次換倉扣 costPerFlip 交易成本。
     * 用 1 日前瞻報酬（不重疊）→ 夏普無自相關虛高問題。
     */
    public StrategyResult backtestStrategy(String key, double deadzone, double costPerFlip) {
        List<double[]> seq = fm.pairs(key, 1);     // (predictor, 次日報酬)，已按日期序
        int n = seq.size();
        if (n < 5) return new StrategyResult(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN);

        double equity = 1.0, peak = 1.0, maxDD = 0.0, bh = 1.0;
        int prevPos = 0, flips = 0, wins = 0, active = 0;
        double[] rets = new double[n];
        for (int i = 0; i < n; i++) {
            double pred = seq.get(i)[0], mktRet = seq.get(i)[1];
            int pos = pred > deadzone ? 1 : pred < -deadzone ? -1 : 0;
            double cost = (pos != prevPos) ? costPerFlip : 0.0;
            if (pos != prevPos) flips++;
            double r = pos * mktRet - cost;
            rets[i] = r;
            equity *= (1 + r);
            bh *= (1 + mktRet);
            peak = Math.max(peak, equity);
            maxDD = Math.min(maxDD, equity / peak - 1);
            if (pos != 0) { active++; if (pos * mktRet > 0) wins++; }
            prevPos = pos;
        }
        double mean = 0; for (double r : rets) mean += r; mean /= n;
        double var = 0; for (double r : rets) var += (r - mean) * (r - mean); var /= n;
        double sd = Math.sqrt(var);
        double sharpe = sd == 0 ? Double.NaN : mean / sd * Math.sqrt(365);  // 加密 24/7 年化
        double winRate = active == 0 ? Double.NaN : (double) wins / active;
        return new StrategyResult(n, equity - 1, sharpe, maxDD, winRate, flips, bh - 1);
    }

    // ============================================================
    // 歷史區間覆蓋率（T020 的歷史重建版）
    // ------------------------------------------------------------
    // 不等 live 預測到期：對每個歷史觀測日，用「該日及之前」最多 90 個
    // 收盤價算滾動月度波動率，再以 BtcQuantAnalyzer.predictPriceRange
    // （與 live 同一份公式，單一真相來源）重建當時會給出的 low/mid/high
    // 區間，對照 D+horizon 的實際價格。無未來函數：每日只用當日以前資料。
    // ============================================================

    public record BandCoverage(
        int n,                  // 有效樣本數
        double coverageRate,    // 實際價落在 low~high 的比例
        double avgBandWidthPct, // 平均區間寬度（佔當日價格 %）
        double midMape,         // 中位預測 vs 實際的平均絕對百分比誤差
        int volFallbackDays     // 滾動波動率無效、套用 0.18 預設的天數
    ) {}

    /** 對 FeatureMatrix 全期重建預測區間並計算覆蓋率。樣本不足回傳 n=0。 */
    public static BandCoverage bandCoverage(FeatureMatrix fm, int horizon) {
        List<Double> closes = new java.util.ArrayList<>();
        int n = 0, hits = 0, fallback = 0;
        double widthSum = 0, apeSum = 0;
        for (FeatureMatrix.Obs o : fm.rows()) {
            closes.add(o.price());
            double ret = o.forwardReturns().getOrDefault(horizon, Double.NaN);
            if (Double.isNaN(ret) || Double.isNaN(o.composite())) continue;

            List<Double> window = closes.subList(Math.max(0, closes.size() - 90), closes.size());
            double vol = com.fiorino.cli.quant.BtcQuantAnalyzer.monthlyVolFromCloses(window);
            if (!(vol > 0.01 && vol < 1.0)) fallback++;   // predictPriceRange 內會套 0.18 預設
            double[] band = com.fiorino.cli.quant.BtcQuantAnalyzer
                .predictPriceRange(o.price(), o.composite(), vol);

            double actual = o.price() * (1 + ret);
            n++;
            if (actual >= band[0] && actual <= band[2]) hits++;
            widthSum += (band[2] - band[0]) / o.price();
            apeSum += Math.abs(band[1] - actual) / actual;
        }
        if (n == 0) return new BandCoverage(0, Double.NaN, Double.NaN, Double.NaN, 0);
        return new BandCoverage(n, (double) hits / n, widthSum / n * 100, apeSum / n * 100, fallback);
    }

    // ============================================================
    // CLI 進入點
    // ============================================================

    public static void runHeadless() {
        System.out.println("📊 Project Fiorino — Track B3 回測（IC / 方向命中率）");
        FeatureMatrix fm = new FeatureMatrixBuilder().build();
        java.util.List<IcRow> rows = new BacktestEngine(fm).run();
        int[] hs = fm.horizons();

        System.out.printf("%n   樣本：%d 觀測日 | horizons=%s%n", fm.size(), java.util.Arrays.toString(hs));
        System.out.println("   ─────────────────────────────────────────────────────────────");
        // IC 表
        System.out.println("   ▸ 資訊係數 IC（predictor vs 未來報酬，Spearman）");
        StringBuilder hdr = new StringBuilder(String.format("     %-18s %4s", "信號", "n"));
        for (int h : hs) hdr.append(String.format(" %14s", "IC_" + h + "d"));
        System.out.println(hdr);
        for (IcRow r : rows) {
            StringBuilder sb = new StringBuilder(String.format("     %-18s %4d", r.key(), r.n()));
            for (double ic : r.icByHorizon())
                sb.append(String.format(" %14s", r.isZeroVariance() ? "零變異" : interpretIc(ic)));
            System.out.println(sb);
        }
        // 命中率表
        System.out.println("   ▸ 方向命中率（sign 一致比例，50% = 無資訊）");
        for (IcRow r : rows) {
            StringBuilder sb = new StringBuilder(String.format("     %-18s     ", r.key()));
            for (double h : r.hitByHorizon())
                sb.append(String.format(" %13s", Double.isNaN(h) ? "—" : String.format("%.1f%%", h * 100)));
            System.out.println(sb);
        }
        // 零變異信號降權建議（資訊量趨近於零）
        java.util.List<IcRow> zeroVar = rows.stream()
            .filter(r -> !r.key().equals("composite") && r.isZeroVariance())
            .toList();
        if (!zeroVar.isEmpty()) {
            System.out.println("   ▸ 零變異信號（樣本內標準差≈0 → IC 無定義，資訊量趨近於零）");
            for (IcRow r : zeroVar)
                System.out.printf("     %-18s 樣本內無波動，當前不貢獻預測力 → 建議 Track B4 校準時降權%n", r.key());
            System.out.println("     （例：近一年 8h 資金費率被釘在基準線，>0.01% 佔比 0%）");
        }
        // 策略回測（composite，每日依方向多空）
        System.out.println("   ─────────────────────────────────────────────────────────────");
        System.out.println("   ▸ 策略回測｜每日依 composite 方向做多/空，持有至次日");
        BacktestEngine eng = new BacktestEngine(fm);
        double deadzone = 10.0, cost = 0.0006;   // 死區 ±10 分；每次換倉 0.06%
        StrategyResult s = eng.backtestStrategy("composite", deadzone, cost);
        if (Double.isNaN(s.annSharpe())) {
            System.out.println("     樣本不足，無法回測。");
        } else {
            System.out.printf("     交易日數    : %d（換倉 %d 次，死區±%.0f，成本 %.2f%%/次）%n",
                s.days(), s.flips(), deadzone, cost * 100);
            System.out.printf("     策略累積報酬: %+.1f%%   ｜  買入持有: %+.1f%%%n",
                s.totalReturn() * 100, s.buyHoldReturn() * 100);
            System.out.printf("     年化夏普    : %.2f%n", s.annSharpe());
            System.out.printf("     最大回撤    : %.1f%%%n", s.maxDrawdown() * 100);
            System.out.printf("     做多空勝率  : %.1f%%%n", s.winRate() * 100);
        }
        // 歷史區間覆蓋率（下月預測「準不準」最直接的歷史量測）
        System.out.println("   ─────────────────────────────────────────────────────────────");
        System.out.println("   ▸ 歷史區間覆蓋率｜重建每日的下月預測區間 vs 30 天後實際價");
        BandCoverage bc = bandCoverage(fm, 30);
        if (bc.n() == 0) {
            System.out.println("     樣本不足，無法計算。");
        } else {
            System.out.printf("     樣本數      : %d 天（其中 %d 天波動率樣本不足、退用 18%% 預設）%n",
                bc.n(), bc.volFallbackDays());
            System.out.printf("     區間覆蓋率  : %.1f%%（30 天後實際價落在 low~high 的比例）%n",
                bc.coverageRate() * 100);
            System.out.printf("     平均區間寬度: ±%.1f%%（low~high 全寬 %.1f%% / 2）%n",
                bc.avgBandWidthPct() / 2, bc.avgBandWidthPct());
            System.out.printf("     中位預測誤差: %.1f%%（pred_mid vs 實際的 MAPE）%n", bc.midMape());
            String verdict = bc.coverageRate() < 0.55 ? "區間明顯偏窄（過度自信）"
                           : bc.coverageRate() < 0.75 ? "接近 ±1σ 理論值（~68%），校準大致合理"
                           : "區間偏寬（保守但低資訊量）";
            System.out.printf("     解讀        : %s%n", verdict);
            System.out.println("     （區間為波動率推導的 ~±1σ 帶，理論覆蓋率基準 ≈ 68%，");
            System.out.println("       不等於報告顯示的啟發式「信心度」%）");
        }
        System.out.println("   ─────────────────────────────────────────────────────────────");
        System.out.println("   ⚠ 警語：多日 horizon 樣本重疊（t 檢定虛高）；365 天=單一格局；");
        System.out.println("     composite 目前僅含 3 個有歷史的信號（實際 2 個有效）→ 非完整模型；");
        System.out.println("     策略未計滑點/資金費率，夏普僅供相對比較。衍生品 5 信號待 Track A 累積；");
        System.out.println("     覆蓋率為歷史重建估計（重疊樣本），真實 track record 待 live 預測到期（Track C）。");
    }
}
