package com.fiorino.cli.quant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BtcQuantAnalyzer 靜態純函數測試（單一真相來源：live 與覆蓋率回測共用）。
 * 驗證月度波動率計算與價格區間預測公式。
 */
class BtcQuantAnalyzerStaticsTest {

    @Test
    @DisplayName("monthlyVolFromCloses：收盤價少於 10 筆 → -1（樣本不足）")
    void volInsufficientSample() {
        assertEquals(-1, BtcQuantAnalyzer.monthlyVolFromCloses(List.of(1.0, 2.0, 3.0)), 1e-12);
        assertEquals(-1, BtcQuantAnalyzer.monthlyVolFromCloses(null), 1e-12);
    }

    @Test
    @DisplayName("monthlyVolFromCloses：定價序列 → 波動率 0")
    void volConstantPrices() {
        List<Double> closes = Collections.nCopies(30, 50_000.0);
        assertEquals(0.0, BtcQuantAnalyzer.monthlyVolFromCloses(closes), 1e-12);
    }

    @Test
    @DisplayName("monthlyVolFromCloses：交替 ±r 日報酬 → 日σ=r、月度=r×√30")
    void volAlternatingReturns() {
        // 收盤交替乘 e^r / e^-r → 對數報酬交替 +r/-r，mean=0、std=r
        double r = 0.02;
        List<Double> closes = new ArrayList<>();
        double p = 100.0;
        closes.add(p);
        for (int i = 0; i < 20; i++) {
            p *= Math.exp(i % 2 == 0 ? r : -r);
            closes.add(p);
        }
        assertEquals(r * Math.sqrt(30), BtcQuantAnalyzer.monthlyVolFromCloses(closes), 1e-9);
    }

    @Test
    @DisplayName("predictPriceRange：composite=0、vol=0.20 → mid=現價、band=±20%（百位捨入）")
    void predictRangeNeutral() {
        double[] band = BtcQuantAnalyzer.predictPriceRange(100_000, 0, 0.20);
        assertEquals(100_000, band[1], 1e-9);
        assertEquals(80_000, band[0], 1e-9);   // volMultiplier=1（|score|=0）
        assertEquals(120_000, band[2], 1e-9);
    }

    @Test
    @DisplayName("predictPriceRange：composite=+100 → mid=+30%，極端分數放寬區間 1.5 倍")
    void predictRangeExtremeBullish() {
        double[] band = BtcQuantAnalyzer.predictPriceRange(100_000, 100, 0.20);
        assertEquals(130_000, band[1], 1e-9);                 // +30% 上限映射
        assertEquals(130_000 * (1 - 0.30), band[0], 100.0);   // adjustedVol = 0.20×1.5
        assertEquals(130_000 * (1 + 0.30), band[2], 100.0);
    }

    @Test
    @DisplayName("predictPriceRange：波動率無效（<=0 或 >=1）→ 退用 0.18 保守預設")
    void predictRangeVolFallback() {
        double[] bandNeg = BtcQuantAnalyzer.predictPriceRange(100_000, 0, -1);
        double[] bandHuge = BtcQuantAnalyzer.predictPriceRange(100_000, 0, 5.0);
        assertEquals(82_000, bandNeg[0], 1e-9);
        assertEquals(118_000, bandNeg[2], 1e-9);
        assertArrayEquals(bandNeg, bandHuge, 1e-9);
    }

    @Test
    @DisplayName("predictPriceRange：無效現價 → 全 0")
    void predictRangeInvalidPrice() {
        assertArrayEquals(new double[]{0, 0, 0},
            BtcQuantAnalyzer.predictPriceRange(0, 50, 0.2), 1e-12);
    }
}
