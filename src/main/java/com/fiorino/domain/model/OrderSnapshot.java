package com.fiorino.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ============================================================
 * OrderSnapshot — 訂單持久化快照模型（Domain Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類是 Domain 與 Infrastructure（H2 持久化）之間的數據契約。
 * 採用 Java 16+ Record 語法，確保不可變性（Immutable）。
 * Record 天生提供 equals(), hashCode(), toString()，消除樣板代碼。
 *
 * 為何需要 OrderSnapshot？
 * 當 JVM 崩潰後重啟，系統需要重建 GridCell 的狀態。
 * 單純依靠 Binance API 查詢所有訂單代價太高（API Weight 消耗大）。
 * 本地快照提供「上次已知狀態」，系統只需差異對賬，而非全量同步。
 *
 * @author benson-code
 * @version 1.0.0
 */
public record OrderSnapshot(
    /** 快照 ID（主鍵，UUID 格式） */
    String snapshotId,

    /** 關聯的 GridCell 索引 */
    int cellIndex,

    /** 交易所訂單 ID（可為 null，代表尚未獲得交易所確認） */
    String exchangeOrderId,

    /** 訂單方向（"BUY" 或 "SELL"） */
    String side,

    /** 掛單價格 */
    BigDecimal price,

    /** 掛單數量 */
    BigDecimal quantity,

    /** 已成交數量 */
    BigDecimal filledQuantity,

    /** GridCell 狀態（序列化為 String 儲存於 DB） */
    String status,

    /** 快照建立時間（Unix 毫秒時間戳） */
    long createdAtMs,

    /** 快照最後更新時間（Unix 毫秒時間戳） */
    long updatedAtMs
) {

    /**
     * 工廠方法：從 GridCell 創建訂單快照。
     *
     * @param cell     來源網格單元
     * @param snapshotId 快照唯一 ID
     * @return 新的訂單快照
     */
    public static OrderSnapshot fromGridCell(GridCell cell, String snapshotId) {
        long now = Instant.now().toEpochMilli();
        return new OrderSnapshot(
            snapshotId,
            cell.getCellIndex(),
            cell.getOrderId(),              // 可能為 null
            cell.getOrderSide().name(),
            cell.getTriggerPrice(),
            cell.getOrderQuantity(),
            cell.getFilledQuantity(),
            cell.getStatus().name(),
            now,
            now
        );
    }

    /**
     * 判斷此快照是否代表一個活躍訂單（需要向交易所核對的訂單）。
     */
    public boolean isActiveOnExchange() {
        return exchangeOrderId != null &&
               !exchangeOrderId.isBlank() &&
               (
                   "ACTIVE".equals(status) ||
                   "PARTIAL".equals(status) ||
                   "PENDING".equals(status)
               );
    }
}
