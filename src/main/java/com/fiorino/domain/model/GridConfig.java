package com.fiorino.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * ============================================================
 * GridConfig — 網格交易策略參數配置（Domain Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類採用「不可變值對象（Immutable Value Object）」設計模式。
 * 網格參數一旦初始化（機器人啟動時），在整個運行週期中不可修改。
 * 這消除了多線程環境下的參數讀取競爭問題，無需任何鎖保護。
 *
 * 數學模型：
 * - 等差網格（Arithmetic Grid）：相鄰格間距固定 = (upperPrice - lowerPrice) / gridCount
 * - 每格掛單 BTC 數量 = totalInvestmentUsdt / (gridCount * midPrice)
 * - 數量需滿足交易所 LOT_SIZE filter：minQty <= qty, qty % stepSize == 0
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class GridConfig {

    /** 網格下界價格（USDT/BTC） */
    private final BigDecimal lowerPrice;

    /** 網格上界價格（USDT/BTC） */
    private final BigDecimal upperPrice;

    /** 網格總格數（建議 10~100 格，格數越多交易越頻繁） */
    private final int gridCount;

    /** 總投入資金（USDT），用於計算每格掛單數量 */
    private final BigDecimal totalInvestmentUsdt;

    /** 每個網格格子的觸發間距（由 lowerPrice, upperPrice, gridCount 計算） */
    private final BigDecimal gridSpacing;

    /** 每格掛單的 BTC 數量（由 totalInvestmentUsdt 和格數計算，並 clamp 至交易所最小量） */
    private final BigDecimal orderQuantityPerGrid;

    /** 交易對符號（例如 "BTCUSDT"） */
    private final String symbol;

    /** 交易所手續費率（Maker，例如 0.001 = 0.1%） */
    private final BigDecimal makerFeeRate;

    /** 交易所 LOT_SIZE 最小下單量（從 ExchangeInfo 讀取，預設 0.001） */
    private final BigDecimal minQuantity;

    /** 交易所 LOT_SIZE 步長（數量必須是此值的整數倍，預設 0.001） */
    private final BigDecimal stepSize;

    /**
     * 構造網格配置（使用預設最小量，適合測試）。
     */
    public GridConfig(String symbol, BigDecimal lowerPrice, BigDecimal upperPrice,
                      int gridCount, BigDecimal totalInvestmentUsdt, BigDecimal makerFeeRate) {
        // Binance BTCUSDT 標準 LOT_SIZE：minQty=0.001, stepSize=0.001
        this(symbol, lowerPrice, upperPrice, gridCount, totalInvestmentUsdt, makerFeeRate,
             new BigDecimal("0.001"), new BigDecimal("0.001"));
    }

    /**
     * 完整構造網格配置（含交易所精度規則）。
     *
     * @param symbol                交易對符號
     * @param lowerPrice            網格下界
     * @param upperPrice            網格上界
     * @param gridCount             格數
     * @param totalInvestmentUsdt   總投入 USDT
     * @param makerFeeRate          手續費率
     * @param minQuantity           交易所 LOT_SIZE 最小量
     * @param stepSize              交易所 LOT_SIZE 步長
     * @throws IllegalArgumentException 如果參數非法
     */
    public GridConfig(String symbol, BigDecimal lowerPrice, BigDecimal upperPrice,
                      int gridCount, BigDecimal totalInvestmentUsdt, BigDecimal makerFeeRate,
                      BigDecimal minQuantity, BigDecimal stepSize) {

        // ---- 嚴格參數校驗 ----
        Objects.requireNonNull(symbol, "交易對符號不能為 null");
        Objects.requireNonNull(lowerPrice, "網格下界不能為 null");
        Objects.requireNonNull(upperPrice, "網格上界不能為 null");
        Objects.requireNonNull(totalInvestmentUsdt, "投資金額不能為 null");
        Objects.requireNonNull(makerFeeRate, "手續費率不能為 null");

        if (symbol.isBlank()) {
            throw new IllegalArgumentException("交易對符號不能為空字串");
        }
        if (lowerPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("網格下界必須大於零: " + lowerPrice);
        }
        if (upperPrice.compareTo(lowerPrice) <= 0) {
            throw new IllegalArgumentException(
                String.format("網格上界(%s)必須大於下界(%s)", upperPrice, lowerPrice)
            );
        }
        if (gridCount < 2) {
            throw new IllegalArgumentException("網格格數必須至少為 2，實際值: " + gridCount);
        }
        if (totalInvestmentUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("投資金額必須大於零: " + totalInvestmentUsdt);
        }
        if (makerFeeRate.compareTo(BigDecimal.ZERO) < 0 ||
            makerFeeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("手續費率必須在 [0, 1) 範圍內: " + makerFeeRate);
        }

        this.symbol = symbol;
        this.lowerPrice = lowerPrice;
        this.upperPrice = upperPrice;
        this.gridCount = gridCount;
        this.totalInvestmentUsdt = totalInvestmentUsdt;
        this.makerFeeRate = makerFeeRate;
        this.minQuantity = Objects.requireNonNullElse(minQuantity, new BigDecimal("0.001"));
        this.stepSize = Objects.requireNonNullElse(stepSize, new BigDecimal("0.001"));

        // ---- 計算派生參數 ----
        // 等差網格間距 = (上界 - 下界) / 格數
        this.gridSpacing = upperPrice.subtract(lowerPrice)
            .divide(BigDecimal.valueOf(gridCount), 8, java.math.RoundingMode.HALF_UP);

        // 中間價格 = (上界 + 下界) / 2
        BigDecimal midPrice = upperPrice.add(lowerPrice)
            .divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);

        // 每格 BTC 掛單量 = 總資金 / (格數 * 中間價)
        BigDecimal rawQty = totalInvestmentUsdt
            .divide(BigDecimal.valueOf(gridCount).multiply(midPrice), 8, java.math.RoundingMode.HALF_DOWN);

        // 對齊 stepSize（向下取整到最近的 stepSize 倍數）
        BigDecimal steppedQty = rawQty
            .divide(this.stepSize, 0, java.math.RoundingMode.FLOOR)
            .multiply(this.stepSize);

        // Clamp 至 minQuantity（確保不低於交易所最小下單量）
        this.orderQuantityPerGrid = steppedQty.max(this.minQuantity);
    }

    // ---- 不可變 Getters（無需加鎖） ----

    public String getSymbol() { return symbol; }
    public BigDecimal getLowerPrice() { return lowerPrice; }
    public BigDecimal getUpperPrice() { return upperPrice; }
    public int getGridCount() { return gridCount; }
    public BigDecimal getTotalInvestmentUsdt() { return totalInvestmentUsdt; }
    public BigDecimal getGridSpacing() { return gridSpacing; }
    public BigDecimal getOrderQuantityPerGrid() { return orderQuantityPerGrid; }
    public BigDecimal getMakerFeeRate() { return makerFeeRate; }
    public BigDecimal getMinQuantity() { return minQuantity; }
    public BigDecimal getStepSize() { return stepSize; }

    /**
     * 計算第 N 格的價格（0-based）。
     *
     * @param cellIndex 格索引（0 為最低價）
     * @return 該格的觸發價格
     */
    public BigDecimal priceAtCell(int cellIndex) {
        if (cellIndex < 0 || cellIndex > gridCount) {
            throw new IllegalArgumentException(
                String.format("格索引 %d 超出範圍 [0, %d]", cellIndex, gridCount)
            );
        }
        return lowerPrice.add(gridSpacing.multiply(BigDecimal.valueOf(cellIndex)));
    }

    @Override
    public String toString() {
        return String.format(
            "GridConfig{symbol=%s, range=[%s, %s], grids=%d, spacing=%s, qtyPerGrid=%s}",
            symbol, lowerPrice, upperPrice, gridCount, gridSpacing, orderQuantityPerGrid
        );
    }
}
