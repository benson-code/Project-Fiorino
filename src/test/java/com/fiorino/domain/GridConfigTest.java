package com.fiorino.domain;

import com.fiorino.domain.model.GridConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GridConfig Unit Tests
 * Verifies grid parameter calculations and validation.
 */
class GridConfigTest {

    @Test
    @DisplayName("Grid spacing should be (upper - lower) / gridCount")
    void testGridSpacing() {
        GridConfig config = new GridConfig("BTCUSDT",
            new BigDecimal("60000"), new BigDecimal("70000"),
            10, new BigDecimal("1000"), new BigDecimal("0.001"));

        BigDecimal expectedSpacing = new BigDecimal("1000.00000000");
        assertEquals(0, expectedSpacing.compareTo(config.getGridSpacing()),
            "Grid spacing should be 1000 USDT");
    }

    @Test
    @DisplayName("priceAtCell(0) should return lower price")
    void testPriceAtCellZero() {
        GridConfig config = new GridConfig("BTCUSDT",
            new BigDecimal("60000"), new BigDecimal("70000"),
            10, new BigDecimal("1000"), new BigDecimal("0.001"));

        assertEquals(0, new BigDecimal("60000").compareTo(config.priceAtCell(0)));
    }

    @Test
    @DisplayName("priceAtCell(10) should return upper price")
    void testPriceAtCellMax() {
        GridConfig config = new GridConfig("BTCUSDT",
            new BigDecimal("60000"), new BigDecimal("70000"),
            10, new BigDecimal("1000"), new BigDecimal("0.001"));

        assertEquals(0, new BigDecimal("70000").compareTo(config.priceAtCell(10)));
    }

    @Test
    @DisplayName("Order quantity should be clamped to minimum LOT_SIZE")
    void testOrderQuantityMinClamp() {
        // With only $1 investment / 10 grids at $65000 mid price, raw qty < 0.001
        GridConfig config = new GridConfig("BTCUSDT",
            new BigDecimal("60000"), new BigDecimal("70000"),
            10, new BigDecimal("1"), new BigDecimal("0.001"),
            new BigDecimal("0.001"), new BigDecimal("0.001"));

        // Should be clamped to minQty
        assertTrue(config.getOrderQuantityPerGrid().compareTo(new BigDecimal("0.001")) >= 0,
            "Quantity should be at least minQuantity");
    }

    @Test
    @DisplayName("Should throw on lower >= upper")
    void testInvalidPriceRange() {
        assertThrows(IllegalArgumentException.class, () ->
            new GridConfig("BTCUSDT",
                new BigDecimal("70000"), new BigDecimal("60000"),
                10, new BigDecimal("1000"), new BigDecimal("0.001"))
        );
    }

    @Test
    @DisplayName("Should throw on gridCount < 2")
    void testInvalidGridCount() {
        assertThrows(IllegalArgumentException.class, () ->
            new GridConfig("BTCUSDT",
                new BigDecimal("60000"), new BigDecimal("70000"),
                1, new BigDecimal("1000"), new BigDecimal("0.001"))
        );
    }

    @Test
    @DisplayName("Should throw on zero investment")
    void testInvalidInvestment() {
        assertThrows(IllegalArgumentException.class, () ->
            new GridConfig("BTCUSDT",
                new BigDecimal("60000"), new BigDecimal("70000"),
                10, BigDecimal.ZERO, new BigDecimal("0.001"))
        );
    }

    @Test
    @DisplayName("Investment of 1000 USDT at 65000 mid price should give >0.001 BTC per grid with 10 grids")
    void testSufficientInvestment() {
        GridConfig config = new GridConfig("BTCUSDT",
            new BigDecimal("60000"), new BigDecimal("70000"),
            10, new BigDecimal("1000"), new BigDecimal("0.001"));

        // 1000 / (10 * 65000) = 0.00153... which is > 0.001
        assertTrue(config.getOrderQuantityPerGrid().compareTo(new BigDecimal("0.001")) >= 0,
            "Quantity should be >= 0.001 BTC with sufficient investment");
    }
}
