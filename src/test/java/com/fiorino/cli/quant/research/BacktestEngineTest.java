package com.fiorino.cli.quant.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BacktestEngine Unit Tests
 * 驗證回測統計（Spearman IC、方向命中率、predictor 標準差）與零變異信號偵測。
 */
class BacktestEngineTest {

    /** 便利建構：交錯的 (predictor, forwardReturn) 配對。 */
    private static List<double[]> pairs(double[] predictors, double[] returns) {
        assertEquals(predictors.length, returns.length, "test data length mismatch");
        List<double[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < predictors.length; i++) out.add(new double[]{predictors[i], returns[i]});
        return out;
    }

    @Test
    @DisplayName("Spearman：完全正單調 → +1.0")
    void spearmanPerfectPositive() {
        var p = pairs(new double[]{1, 2, 3, 4, 5}, new double[]{2, 4, 6, 8, 10});
        assertEquals(1.0, BacktestEngine.spearman(p), 1e-9);
    }

    @Test
    @DisplayName("Spearman：完全負單調 → -1.0")
    void spearmanPerfectNegative() {
        var p = pairs(new double[]{1, 2, 3, 4, 5}, new double[]{10, 8, 6, 4, 2});
        assertEquals(-1.0, BacktestEngine.spearman(p), 1e-9);
    }

    @Test
    @DisplayName("Spearman：樣本不足（n<5）→ NaN")
    void spearmanInsufficientSample() {
        var p = pairs(new double[]{1, 2, 3, 4}, new double[]{2, 4, 6, 8});
        assertTrue(Double.isNaN(BacktestEngine.spearman(p)));
    }

    @Test
    @DisplayName("Spearman：predictor 零變異 → NaN（IC 無定義）")
    void spearmanZeroVariancePredictor() {
        var p = pairs(new double[]{5, 5, 5, 5, 5}, new double[]{1, 2, 3, 4, 5});
        assertTrue(Double.isNaN(BacktestEngine.spearman(p)));
    }

    @Test
    @DisplayName("方向命中率：predictor=0 不計，其餘比對 sign")
    void directionalHitSkipsZero() {
        // (1,1)hit (-1,-1)hit (1,-1)miss (0,99)skip → 2/3
        var p = pairs(new double[]{1, -1, 1, 0}, new double[]{1, -1, -1, 99});
        assertEquals(2.0 / 3.0, BacktestEngine.directionalHit(p), 1e-9);
    }

    @Test
    @DisplayName("方向命中率：全空手（predictor 皆 0）→ NaN")
    void directionalHitAllZero() {
        var p = pairs(new double[]{0, 0, 0}, new double[]{1, -2, 3});
        assertTrue(Double.isNaN(BacktestEngine.directionalHit(p)));
    }

    @Test
    @DisplayName("predictorStdev：常數序列 → 0；空樣本 → NaN")
    void predictorStdevEdgeCases() {
        assertEquals(0.0, BacktestEngine.predictorStdev(
            pairs(new double[]{7, 7, 7}, new double[]{1, 2, 3})), 1e-12);
        assertTrue(Double.isNaN(BacktestEngine.predictorStdev(List.of())));
    }

    @Test
    @DisplayName("predictorStdev：母體標準差計算正確")
    void predictorStdevValue() {
        // predictor [1,2,3,4,5] mean=3, var=2, std=sqrt(2)
        var p = pairs(new double[]{1, 2, 3, 4, 5}, new double[]{0, 0, 0, 0, 0});
        assertEquals(Math.sqrt(2.0), BacktestEngine.predictorStdev(p), 1e-9);
    }

    @Test
    @DisplayName("interpretIc：NaN → 「—」；數值含量級標籤")
    void interpretIcFormatting() {
        assertEquals("—", BacktestEngine.interpretIc(Double.NaN));
        assertTrue(BacktestEngine.interpretIc(0.02).contains("可忽略"));
        assertTrue(BacktestEngine.interpretIc(0.25).contains("顯著"));
        assertTrue(BacktestEngine.interpretIc(0.25).startsWith("+0.250"));
    }

    @Test
    @DisplayName("IcRow.isZeroVariance：低於 EPS 判定為零變異")
    void icRowZeroVarianceFlag() {
        var zero = new BacktestEngine.IcRow("funding_rate", 30,
            new double[]{Double.NaN}, new double[]{Double.NaN}, 0.0);
        var live = new BacktestEngine.IcRow("composite", 30,
            new double[]{0.2}, new double[]{0.55}, 12.3);
        assertTrue(zero.isZeroVariance());
        assertFalse(live.isZeroVariance());
    }

    // ============================================================
    // 歷史區間覆蓋率（bandCoverage）
    // ============================================================

    /** 便利建構：定價序列 + composite 全 0 的特徵矩陣（horizon 固定 30）。 */
    private static FeatureMatrix flatMatrix(int days, double price, double ret30) {
        List<FeatureMatrix.Obs> rows = new java.util.ArrayList<>();
        java.time.LocalDate d0 = java.time.LocalDate.of(2025, 1, 1);
        for (int i = 0; i < days; i++) {
            var fwd = new java.util.LinkedHashMap<Integer, Double>();
            fwd.put(30, ret30);
            rows.add(new FeatureMatrix.Obs(d0.plusDays(i), price, 0.0, java.util.Map.of(), fwd));
        }
        return new FeatureMatrix(List.of(), new int[]{30}, rows);
    }

    @Test
    @DisplayName("bandCoverage：價格持平（ret=0）→ 實際價必在區間內，覆蓋率 100%")
    void bandCoverageFlatPriceFullyCovered() {
        // 定價 → 滾動波動率 0（無效）→ 退用 0.18 預設；composite=0 → mid=price，band=±18%
        var bc = BacktestEngine.bandCoverage(flatMatrix(20, 100_000, 0.0), 30);
        assertEquals(20, bc.n());
        assertEquals(1.0, bc.coverageRate(), 1e-9);
        assertEquals(20, bc.volFallbackDays());       // 全程波動率無效
        assertEquals(36.0, bc.avgBandWidthPct(), 0.5); // 全寬 ≈ ±18% × 2
        assertEquals(0.0, bc.midMape(), 0.2);          // mid=price=實際（rounding 容差）
    }

    @Test
    @DisplayName("bandCoverage：30 天後大漲 50% → 超出 ±18% 區間，覆蓋率 0%")
    void bandCoverageBigMoveMissed() {
        var bc = BacktestEngine.bandCoverage(flatMatrix(20, 100_000, 0.50), 30);
        assertEquals(20, bc.n());
        assertEquals(0.0, bc.coverageRate(), 1e-9);
        assertEquals(100.0 / 3.0, bc.midMape(), 0.5);  // |100k-150k|/150k ≈ 33.3%
    }

    @Test
    @DisplayName("bandCoverage：無前瞻報酬（全 NaN）→ n=0，指標為 NaN")
    void bandCoverageNoSamples() {
        var bc = BacktestEngine.bandCoverage(flatMatrix(10, 100_000, Double.NaN), 30);
        assertEquals(0, bc.n());
        assertTrue(Double.isNaN(bc.coverageRate()));
    }
}
