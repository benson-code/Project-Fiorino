package com.fiorino.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.StampedLock;

/**
 * ============================================================
 * GridCell — 網格單元領域模型（Domain Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類是整個網格交易系統的最小原子單位。每個 GridCell 代表
 * 一個價格區間，持有其對應的買單或賣單。
 *
 * 並發設計核心：StampedLock（樂觀讀 + 悲觀寫）
 * -----------------------------------------
 * 傳統 synchronized / ReentrantReadWriteLock 在高讀取場景下仍有
 * 讀鎖競爭問題。Java 8+ 的 StampedLock 提供「樂觀讀」模式：
 *   1. 讀操作：先嘗試樂觀讀（不加鎖），驗證 stamp 未變則直接返回。
 *   2. 寫操作（狀態變更、訂單 ID 更新）：升級為悲觀寫鎖，確保原子性。
 *
 * 這使得 Dashboard 線程（高頻讀取所有 Cell 狀態）與
 * OrderExecutor 線程（低頻寫入特定 Cell）之間達到近乎零競爭。
 *
 * 防禦性設計：
 * - 所有 BigDecimal 欄位初始化為 BigDecimal.ZERO，杜絕 NPE。
 * - orderId 採用 String 而非 long，因 Binance order ID 可能超出 Long 範圍。
 * - 狀態比對一律使用 Objects.equals()，禁止 == 比對 String。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class GridCell {

    // ============================================================
    // 網格單元狀態枚舉
    // ============================================================

    /**
     * 網格單元的完整生命週期狀態：
     *
     * EMPTY       → 初始化完成，尚未掛單
     * PENDING     → 已提交下單請求，等待交易所確認（API 回應前的過渡態）
     * ACTIVE      → 訂單已在交易所掛出，等待成交
     * PARTIAL     → 訂單部分成交（Partial Fill），仍在等待剩餘成交
     * FILLED      → 訂單完全成交，準備執行反向掛單
     * CANCELLED   → 訂單已撤銷（用戶手動撤銷或系統風控撤銷）
     * ERROR       → 下單或狀態查詢發生異常，需人工介入
     *
     * 狀態轉移圖：
     *   EMPTY → PENDING → ACTIVE → PARTIAL → FILLED → EMPTY (循環)
     *                   ↘ CANCELLED
     *                   ↘ ERROR
     */
    public enum CellStatus {
        EMPTY,      // 空閒，待掛單
        PENDING,    // 掛單請求已發送，等待確認
        ACTIVE,     // 訂單在交易所活躍中
        PARTIAL,    // 部分成交
        FILLED,     // 完全成交
        CANCELLED,  // 已撤銷
        ERROR       // 異常狀態
    }

    /**
     * 網格單元訂單方向：買入（BUY）或賣出（SELL）
     */
    public enum OrderSide {
        BUY,
        SELL
    }

    // ============================================================
    // 核心欄位（不可變配置 + 可變狀態）
    // ============================================================

    /** 網格單元索引（0-based，從最低價格開始） */
    private final int cellIndex;

    /** 本格的觸發買入/賣出價格（不可變，初始化後固定） */
    private final BigDecimal triggerPrice;

    /** 本格的掛單數量（BTC 數量，不可變，初始化後固定） */
    private final BigDecimal orderQuantity;

    /**
     * 本格的訂單方向（BUY 或 SELL）。
     * 架構決策：設為 volatile 而非 final，允許在成交後根據反向掛單邏輯更新方向。
     * 網格策略核心：BUY 成交 → 上方格改為 SELL；SELL 成交 → 下方格改為 BUY。
     * 所有寫操作必須在 StampedLock 寫鎖保護下進行。
     */
    private volatile OrderSide orderSide;

    // ---- 以下為可變狀態，所有訪問必須通過 StampedLock 保護 ----

    /** 當前訂單狀態（volatile 保證可見性） */
    private volatile CellStatus status;

    /** 當前在交易所的訂單 ID（nullable，EMPTY/ERROR 狀態下為 null） */
    private volatile String orderId;

    /** 已成交的數量（累計，支援部分成交場景） */
    private volatile BigDecimal filledQuantity;

    /** 已成交的平均成交價格 */
    private volatile BigDecimal avgFillPrice;

    /** 最後一次狀態變更的時間戳記 */
    private volatile Instant lastUpdatedAt;

    /** 本格產生的已實現盈虧（USDT）— 每次完整成交後累計 */
    private volatile BigDecimal realizedPnl;

    // ============================================================
    // 細粒度鎖：每個 GridCell 持有獨立的 StampedLock
    // 架構決策：不使用全局鎖，確保不同 Cell 的操作可完全並行
    // ============================================================
    private final StampedLock lock = new StampedLock();

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 構造一個新的網格單元。
     *
     * @param cellIndex     單元索引（≥ 0）
     * @param triggerPrice  觸發價格（必須 > 0）
     * @param orderQuantity 掛單數量（必須 > 0）
     * @param orderSide     訂單方向
     * @throws IllegalArgumentException 如果價格或數量非法
     */
    public GridCell(int cellIndex, BigDecimal triggerPrice, BigDecimal orderQuantity, OrderSide orderSide) {
        // 防禦性參數校驗
        if (cellIndex < 0) {
            throw new IllegalArgumentException("GridCell 索引不能為負數: " + cellIndex);
        }
        Objects.requireNonNull(triggerPrice, "觸發價格不能為 null");
        Objects.requireNonNull(orderQuantity, "掛單數量不能為 null");
        Objects.requireNonNull(orderSide, "訂單方向不能為 null");

        if (triggerPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("觸發價格必須大於零，實際值: " + triggerPrice);
        }
        if (orderQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("掛單數量必須大於零，實際值: " + orderQuantity);
        }

        this.cellIndex = cellIndex;
        this.triggerPrice = triggerPrice;
        this.orderQuantity = orderQuantity;
        this.orderSide = orderSide;

        // 初始化可變狀態
        this.status = CellStatus.EMPTY;
        this.orderId = null;
        this.filledQuantity = BigDecimal.ZERO;
        this.avgFillPrice = BigDecimal.ZERO;
        this.realizedPnl = BigDecimal.ZERO;
        this.lastUpdatedAt = Instant.now();
    }

    // ============================================================
    // 寫操作：狀態轉移方法（需要悲觀寫鎖）
    // ============================================================

    /**
     * 【寫操作】將狀態轉移到 PENDING，記錄下單請求已發送。
     * 在發送 API 請求之前調用，確保中間態被持久化。
     *
     * @throws IllegalStateException 如果當前狀態不允許轉移到 PENDING
     */
    public void transitionToPending() {
        long stamp = lock.writeLock();
        try {
            if (!Objects.equals(CellStatus.EMPTY, this.status) &&
                !Objects.equals(CellStatus.FILLED, this.status)) {
                throw new IllegalStateException(
                    String.format("GridCell[%d] 無法從 %s 轉移到 PENDING 狀態", cellIndex, this.status)
                );
            }
            this.status = CellStatus.PENDING;
            this.orderId = null; // 尚未獲得交易所 orderId
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】將狀態轉移到 ACTIVE，記錄交易所返回的訂單 ID。
     *
     * @param newOrderId 交易所返回的訂單 ID（不能為空）
     * @throws IllegalArgumentException 如果 orderId 為空
     * @throws IllegalStateException    如果狀態不允許轉移
     */
    public void transitionToActive(String newOrderId) {
        Objects.requireNonNull(newOrderId, "訂單 ID 不能為 null");
        if (newOrderId.isBlank()) {
            throw new IllegalArgumentException("訂單 ID 不能為空字串");
        }

        long stamp = lock.writeLock();
        try {
            if (!Objects.equals(CellStatus.PENDING, this.status)) {
                throw new IllegalStateException(
                    String.format("GridCell[%d] 無法從 %s 轉移到 ACTIVE 狀態，必須先為 PENDING",
                        cellIndex, this.status)
                );
            }
            this.status = CellStatus.ACTIVE;
            this.orderId = newOrderId;
            this.filledQuantity = BigDecimal.ZERO; // 重置成交量
            this.avgFillPrice = BigDecimal.ZERO;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】更新部分成交狀態（Partial Fill）。
     *
     * @param newFilledQty  最新累計成交量
     * @param newAvgPrice   最新加權平均成交價
     */
    public void updatePartialFill(BigDecimal newFilledQty, BigDecimal newAvgPrice) {
        Objects.requireNonNull(newFilledQty, "成交量不能為 null");
        Objects.requireNonNull(newAvgPrice, "成交價不能為 null");

        long stamp = lock.writeLock();
        try {
            // 只有 ACTIVE 或 PARTIAL 狀態才允許部分成交更新
            if (!Objects.equals(CellStatus.ACTIVE, this.status) &&
                !Objects.equals(CellStatus.PARTIAL, this.status)) {
                throw new IllegalStateException(
                    String.format("GridCell[%d] 狀態為 %s，不能更新部分成交", cellIndex, this.status)
                );
            }
            this.status = CellStatus.PARTIAL;
            this.filledQuantity = newFilledQty;
            this.avgFillPrice = newAvgPrice;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】標記為完全成交（Filled），計算本次已實現盈虧。
     *
     * @param finalFilledQty   最終成交數量
     * @param finalAvgPrice    最終加權平均成交價
     * @param pnlContribution  本次成交貢獻的已實現盈虧（正為盈利）
     */
    public void transitionToFilled(BigDecimal finalFilledQty, BigDecimal finalAvgPrice,
                                    BigDecimal pnlContribution) {
        Objects.requireNonNull(finalFilledQty, "成交數量不能為 null");
        Objects.requireNonNull(finalAvgPrice, "成交價格不能為 null");
        Objects.requireNonNull(pnlContribution, "盈虧貢獻不能為 null");

        long stamp = lock.writeLock();
        try {
            if (Objects.equals(CellStatus.CANCELLED, this.status) ||
                Objects.equals(CellStatus.ERROR, this.status)) {
                throw new IllegalStateException(
                    String.format("GridCell[%d] 狀態為 %s，不能轉移到 FILLED", cellIndex, this.status)
                );
            }
            this.status = CellStatus.FILLED;
            this.filledQuantity = finalFilledQty;
            this.avgFillPrice = finalAvgPrice;
            // 累加已實現盈虧（不重置，持續累計本格歷史盈虧）
            this.realizedPnl = this.realizedPnl.add(pnlContribution);
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】重置為 EMPTY 狀態（在完成成交後，等待反向掛單前調用）。
     */
    public void resetToEmpty() {
        long stamp = lock.writeLock();
        try {
            this.status = CellStatus.EMPTY;
            this.orderId = null;
            this.filledQuantity = BigDecimal.ZERO;
            this.avgFillPrice = BigDecimal.ZERO;
            this.lastUpdatedAt = Instant.now();
            // 注意：realizedPnl 不清零，保留歷史記錄
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】初始化已實現盈虧（主要用於啟動時從資料庫還原歷史數據）。
     *
     * @param pnl 歷史累計盈虧
     */
    public void initRealizedPnl(BigDecimal pnl) {
        Objects.requireNonNull(pnl, "盈虧不能為 null");
        long stamp = lock.writeLock();
        try {
            this.realizedPnl = pnl;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】標記為撤銷狀態。
     */
    public void transitionToCancelled() {
        long stamp = lock.writeLock();
        try {
            this.status = CellStatus.CANCELLED;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * 【寫操作】標記為錯誤狀態，需要人工介入或自動恢復。
     */
    public void transitionToError() {
        long stamp = lock.writeLock();
        try {
            this.status = CellStatus.ERROR;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ============================================================
    // 讀操作：使用樂觀讀（Optimistic Read）降低鎖競爭
    // ============================================================

    /**
     * 【樂觀讀】獲取當前狀態快照。
     * 優先嘗試樂觀讀（不加鎖），失敗時升級為讀鎖。
     *
     * @return 當前單元狀態
     */
    public CellStatus getStatus() {
        // 步驟1：嘗試樂觀讀
        long stamp = lock.tryOptimisticRead();
        CellStatus snapshot = this.status;

        // 步驟2：驗證期間是否有寫操作發生
        if (!lock.validate(stamp)) {
            // 樂觀讀失敗，升級為悲觀讀鎖
            stamp = lock.readLock();
            try {
                snapshot = this.status;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return snapshot;
    }

    /**
     * 【樂觀讀】獲取當前訂單 ID（可能為 null）。
     */
    public String getOrderId() {
        long stamp = lock.tryOptimisticRead();
        String snapshot = this.orderId;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                snapshot = this.orderId;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return snapshot;
    }

    /**
     * 【樂觀讀】獲取當前累計成交量。
     */
    public BigDecimal getFilledQuantity() {
        long stamp = lock.tryOptimisticRead();
        BigDecimal snapshot = this.filledQuantity;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                snapshot = this.filledQuantity;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        // 防禦性空值保護：即使快照為 null 也不拋出 NPE
        return Objects.requireNonNullElse(snapshot, BigDecimal.ZERO);
    }

    /**
     * 【樂觀讀】獲取當前已累計的已實現盈虧。
     */
    public BigDecimal getRealizedPnl() {
        long stamp = lock.tryOptimisticRead();
        BigDecimal snapshot = this.realizedPnl;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                snapshot = this.realizedPnl;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return Objects.requireNonNullElse(snapshot, BigDecimal.ZERO);
    }

    /**
     * 【樂觀讀】獲取最後更新時間。
     */
    public Instant getLastUpdatedAt() {
        long stamp = lock.tryOptimisticRead();
        Instant snapshot = this.lastUpdatedAt;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                snapshot = this.lastUpdatedAt;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return Objects.requireNonNullElse(snapshot, Instant.now());
    }

    // ============================================================
    // 不可變欄位的 Getters（無需加鎖）
    // ============================================================

    public int getCellIndex() {
        return cellIndex;
    }

    public BigDecimal getTriggerPrice() {
        return triggerPrice;
    }

    public BigDecimal getOrderQuantity() {
        return orderQuantity;
    }

    /**
     * 【樂觀讀】獲取當前訂單方向。
     * 使用樂觀讀降低競爭（方向更新頻率極低）。
     */
    public OrderSide getOrderSide() {
        long stamp = lock.tryOptimisticRead();
        OrderSide snapshot = this.orderSide;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                snapshot = this.orderSide;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return snapshot;
    }

    /**
     * 【寫操作】更新訂單方向（在成交後觸發反向掛單前調用）。
     * 網格交易核心邏輯：BUY 成交 → 反向格設為 SELL；SELL 成交 → 反向格設為 BUY。
     *
     * @param newSide 新的訂單方向（不能為 null）
     * @throws IllegalArgumentException 如果 newSide 為 null
     */
    public void updateOrderSide(OrderSide newSide) {
        Objects.requireNonNull(newSide, "訂單方向不能為 null");
        long stamp = lock.writeLock();
        try {
            this.orderSide = newSide;
            this.lastUpdatedAt = Instant.now();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 判斷當前格是否需要掛單（EMPTY 狀態）。
     */
    public boolean needsOrder() {
        return Objects.equals(CellStatus.EMPTY, getStatus());
    }

    /**
     * 判斷當前格是否有活躍訂單（ACTIVE 或 PARTIAL）。
     */
    public boolean hasActiveOrder() {
        CellStatus current = getStatus();
        return Objects.equals(CellStatus.ACTIVE, current) ||
               Objects.equals(CellStatus.PARTIAL, current);
    }

    @Override
    public String toString() {
        return String.format("GridCell{index=%d, price=%s, side=%s, status=%s, orderId=%s}",
            cellIndex, triggerPrice, orderSide, getStatus(), getOrderId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GridCell other)) return false;
        return cellIndex == other.cellIndex;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(cellIndex);
    }
}
