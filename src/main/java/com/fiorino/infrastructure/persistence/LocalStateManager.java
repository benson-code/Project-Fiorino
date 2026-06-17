package com.fiorino.infrastructure.persistence;

import com.fiorino.domain.model.GridCell;
import com.fiorino.domain.model.GridCell.CellStatus;
import com.fiorino.domain.model.GridCell.OrderSide;
import com.fiorino.domain.model.OrderSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * ============================================================
 * LocalStateManager — H2 嵌入式資料庫狀態管理器（Infrastructure Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類實作「WAL-like（Write-Ahead Logging）」持久化策略：
 *
 *   1. 「先寫 DB，再執行 API」：
 *      下單前先在 H2 記錄 PENDING 狀態。
 *      即使 JVM 在下單 API 調用後崩潰，重啟時知道曾發送請求，
 *      並會主動查詢 Binance 確認此訂單是否真實存在。
 *
 *   2. 重啟恢復邏輯（Crash Recovery）：
 *      a. 讀取 H2 快照中所有非 EMPTY/CANCELLED/ERROR 的訂單
 *      b. 對每個有 exchangeOrderId 的訂單，調用 Binance GET /order 核對實際狀態
 *      c. 若 Binance 無此訂單（已成交或被撤銷），更新本地狀態
 *      d. 若本地為 PENDING 但 Binance 無記錄，視為下單失敗，重置為 EMPTY
 *
 * H2 配置策略：
 *   - 使用 FILE 模式（非 IN-MEMORY）：`jdbc:h2:file:./data/fiorino_state`
 *   - 啟用 WAL 模式：`CACHE_SIZE=8192;LOCK_MODE=3;WRITE_DELAY=0`
 *   - HikariCP 連線池：最大 5 個連線（本地 DB 不需要太多）
 *
 * 線程安全：
 *   JDBC 操作本身不是線程安全的，但 HikariCP 連線池提供連線級別的隔離。
 *   多個 Virtual Thread 可以並發調用本類方法，
 *   每個調用從池中獲取獨立連線，無競爭。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class LocalStateManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalStateManager.class);

    // ============================================================
    // H2 資料庫配置
    // ============================================================

    private static final String DB_PATH = "./data/fiorino_state";
    private static final String DB_URL_TEMPLATE =
        "jdbc:h2:file:%s;CACHE_SIZE=32768;LOCK_MODE=3;WRITE_DELAY=0;AUTO_RECONNECT=TRUE;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0";

    // 主資料表名稱
    private static final String TABLE_ORDERS = "GRID_ORDER_SNAPSHOTS";
    private static final String TABLE_BOT_STATE = "BOT_STATE";
    private static final String TABLE_TRADE_HISTORY = "TRADE_HISTORY";

    // ============================================================
    // 核心欄位
    // ============================================================

    private final HikariDataSource dataSource;
    private volatile boolean initialized = false;

    // ============================================================
    // 構造器與初始化
    // ============================================================

    /**
     * 初始化 LocalStateManager，建立 HikariCP 連線池並創建 DDL 資料表。
     *
     * @param dbPath 資料庫文件路徑（不含擴展名），null 使用預設路徑
     */
    public LocalStateManager(String dbPath) {
        String resolvedPath = Objects.requireNonNullElse(dbPath, DB_PATH);
        String jdbcUrl = String.format(DB_URL_TEMPLATE, resolvedPath);

        log.info("初始化 LocalStateManager | DB 路徑: {}", resolvedPath);

        // 配置 HikariCP 連線池
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.h2.Driver");
        config.setUsername("fiorino");
        config.setPassword(""); // 本地 DB，無密碼（生產環境可配置）

        // 連線池大小：小型本地 DB 無需太多連線
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(3000);  // 3 秒連線超時
        config.setIdleTimeout(300000);      // 5 分鐘空閒超時
        config.setMaxLifetime(600000);      // 10 分鐘最大生命週期
        config.setPoolName("FiorinoH2Pool");

        // 連線測試語句（確認連線有效）
        config.setConnectionTestQuery("SELECT 1");

        // H2 特定優化選項
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);

        // 初始化資料表結構
        initializeSchema();
        this.initialized = true;
        log.info("LocalStateManager 初始化完成");
    }

    /**
     * 使用預設 DB 路徑創建 LocalStateManager。
     */
    public LocalStateManager() {
        this(null);
    }

    /**
     * 創建所有必要的資料表（若不存在）。
     * 使用 CREATE TABLE IF NOT EXISTS 確保冪等性（重複執行安全）。
     */
    private void initializeSchema() {
        log.info("初始化資料庫 Schema...");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {

                // === 主資料表：網格訂單快照 ===
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        snapshot_id         VARCHAR(64)    NOT NULL,
                        cell_index          INT            NOT NULL,
                        exchange_order_id   VARCHAR(32),
                        side                VARCHAR(8)     NOT NULL,
                        price               DECIMAL(20,8)  NOT NULL,
                        quantity            DECIMAL(20,8)  NOT NULL,
                        filled_quantity     DECIMAL(20,8)  NOT NULL DEFAULT 0,
                        status              VARCHAR(20)    NOT NULL,
                        created_at_ms       BIGINT         NOT NULL,
                        updated_at_ms       BIGINT         NOT NULL,
                        PRIMARY KEY (snapshot_id)
                    )
                    """.formatted(TABLE_ORDERS));

                // 獨立建立索引（H2 v2.x 不支援 CREATE TABLE 內的 INDEX 子句）
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_cell ON "
                    + TABLE_ORDERS + "(cell_index)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status ON "
                    + TABLE_ORDERS + "(status)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_oid ON "
                    + TABLE_ORDERS + "(exchange_order_id)");

                // === Bot 全局狀態表（單行設計：id 固定為 1） ===
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id                  INT            NOT NULL DEFAULT 1,
                        bot_state           VARCHAR(30)    NOT NULL DEFAULT 'INIT',
                        symbol              VARCHAR(20)    NOT NULL DEFAULT 'BTCUSDT',
                        lower_price         DECIMAL(20,8),
                        upper_price         DECIMAL(20,8),
                        grid_count          INT,
                        total_investment    DECIMAL(20,8),
                        total_realized_pnl  DECIMAL(20,8)  NOT NULL DEFAULT 0,
                        start_time_ms       BIGINT,
                        last_update_ms      BIGINT         NOT NULL,
                        PRIMARY KEY (id)
                    )
                    """.formatted(TABLE_BOT_STATE));

                // === 歷史成交記錄表（用於 P&L 審計） ===
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        trade_id            VARCHAR(64)    NOT NULL,
                        cell_index          INT            NOT NULL,
                        exchange_order_id   VARCHAR(32)    NOT NULL,
                        side                VARCHAR(8)     NOT NULL,
                        fill_price          DECIMAL(20,8)  NOT NULL,
                        fill_quantity       DECIMAL(20,8)  NOT NULL,
                        realized_pnl        DECIMAL(20,8)  NOT NULL DEFAULT 0,
                        trade_time_ms       BIGINT         NOT NULL,
                        PRIMARY KEY (trade_id)
                    )
                    """.formatted(TABLE_TRADE_HISTORY));

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_trade_cell ON "
                    + TABLE_TRADE_HISTORY + "(cell_index)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_trade_time ON "
                    + TABLE_TRADE_HISTORY + "(trade_time_ms)");
            }

            log.info("資料庫 Schema 初始化完成");

        } catch (SQLException e) {
            log.error("資料庫 Schema 初始化失敗", e);
            throw new RuntimeException("無法初始化 H2 資料庫 Schema", e);
        }
    }

    // ============================================================
    // 訂單快照 CRUD 操作
    // ============================================================

    /**
     * 保存或更新一個網格單元的訂單快照。
     * 使用 MERGE INTO（H2 的 Upsert 語法），確保同一 cellIndex 只有一條記錄。
     *
     * @param snapshot 要保存的訂單快照
     */
    public void upsertOrderSnapshot(OrderSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "OrderSnapshot 不能為 null");
        checkInitialized();

        String sql = """
            MERGE INTO %s
                (snapshot_id, cell_index, exchange_order_id, side, price, quantity,
                 filled_quantity, status, created_at_ms, updated_at_ms)
            KEY (cell_index)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TABLE_ORDERS);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, snapshot.snapshotId());
            pstmt.setInt(2, snapshot.cellIndex());
            pstmt.setString(3, snapshot.exchangeOrderId());   // 可為 null
            pstmt.setString(4, snapshot.side());
            pstmt.setBigDecimal(5, snapshot.price());
            pstmt.setBigDecimal(6, snapshot.quantity());
            pstmt.setBigDecimal(7, snapshot.filledQuantity());
            pstmt.setString(8, snapshot.status());
            pstmt.setLong(9, snapshot.createdAtMs());
            pstmt.setLong(10, snapshot.updatedAtMs());

            int rows = pstmt.executeUpdate();
            log.debug("快照保存完成 | cellIndex={} status={} rows={}", snapshot.cellIndex(), snapshot.status(), rows);

        } catch (SQLException e) {
            log.error("保存訂單快照失敗 | cellIndex={} status={}", snapshot.cellIndex(), snapshot.status(), e);
            throw new RuntimeException("H2 寫入失敗", e);
        }
    }

    /**
     * 批量更新多個快照（用於啟動對賬後的批量狀態更新）。
     * 使用 JDBC Batch，性能遠優於逐一更新。
     *
     * @param snapshots 要更新的快照列表
     */
    public void batchUpsertOrderSnapshots(List<OrderSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        checkInitialized();

        String sql = """
            MERGE INTO %s
                (snapshot_id, cell_index, exchange_order_id, side, price, quantity,
                 filled_quantity, status, created_at_ms, updated_at_ms)
            KEY (cell_index)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TABLE_ORDERS);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);  // 開啟事務，確保批量操作的原子性

            for (OrderSnapshot snapshot : snapshots) {
                pstmt.setString(1, snapshot.snapshotId());
                pstmt.setInt(2, snapshot.cellIndex());
                pstmt.setString(3, snapshot.exchangeOrderId());
                pstmt.setString(4, snapshot.side());
                pstmt.setBigDecimal(5, snapshot.price());
                pstmt.setBigDecimal(6, snapshot.quantity());
                pstmt.setBigDecimal(7, snapshot.filledQuantity());
                pstmt.setString(8, snapshot.status());
                pstmt.setLong(9, snapshot.createdAtMs());
                pstmt.setLong(10, snapshot.updatedAtMs());
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            conn.commit();
            log.info("批量快照保存完成 | 數量: {}", snapshots.size());

        } catch (SQLException e) {
            log.error("批量快照保存失敗", e);
            throw new RuntimeException("H2 批量寫入失敗", e);
        }
    }

    /**
     * 讀取所有需要對賬的訂單快照（狀態不為 EMPTY/CANCELLED/STOPPED）。
     * 用於重啟後的崩潰恢復。
     *
     * @return 需要對賬的快照列表（不可修改）
     */
    public List<OrderSnapshot> loadActiveSnapshots() {
        checkInitialized();

        String sql = """
            SELECT snapshot_id, cell_index, exchange_order_id, side, price, quantity,
                   filled_quantity, status, created_at_ms, updated_at_ms
            FROM %s
            WHERE status NOT IN ('EMPTY', 'CANCELLED', 'STOPPED')
            ORDER BY cell_index
            """.formatted(TABLE_ORDERS);

        List<OrderSnapshot> snapshots = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                OrderSnapshot snapshot = mapRowToSnapshot(rs);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }

            log.info("讀取活躍快照完成 | 數量: {}", snapshots.size());
            return Collections.unmodifiableList(snapshots);

        } catch (SQLException e) {
            log.error("讀取活躍快照失敗", e);
            throw new RuntimeException("H2 讀取失敗", e);
        }
    }

    /**
     * 讀取所有快照（用於完整狀態恢復）。
     *
     * @return 所有快照列表（按 cellIndex 排序）
     */
    public List<OrderSnapshot> loadAllSnapshots() {
        checkInitialized();

        String sql = """
            SELECT snapshot_id, cell_index, exchange_order_id, side, price, quantity,
                   filled_quantity, status, created_at_ms, updated_at_ms
            FROM %s
            ORDER BY cell_index
            """.formatted(TABLE_ORDERS);

        List<OrderSnapshot> snapshots = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                OrderSnapshot snapshot = mapRowToSnapshot(rs);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }

            log.info("讀取全部快照完成 | 數量: {}", snapshots.size());
            return Collections.unmodifiableList(snapshots);

        } catch (SQLException e) {
            log.error("讀取全部快照失敗", e);
            throw new RuntimeException("H2 讀取失敗", e);
        }
    }

    /**
     * 刪除指定格索引的快照（例如，在格完全重置時）。
     *
     * @param cellIndex 要刪除快照的格索引
     */
    public void deleteSnapshot(int cellIndex) {
        checkInitialized();

        String sql = "DELETE FROM " + TABLE_ORDERS + " WHERE cell_index = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, cellIndex);
            int rows = pstmt.executeUpdate();
            log.debug("刪除快照 | cellIndex={} 影響行數: {}", cellIndex, rows);

        } catch (SQLException e) {
            log.error("刪除快照失敗 | cellIndex={}", cellIndex, e);
            throw new RuntimeException("H2 刪除失敗", e);
        }
    }

    // ============================================================
    // 成交歷史記錄
    // ============================================================

    /**
     * 記錄一筆成交歷史（用於 P&L 審計和報表）。
     *
     * @param tradeId         唯一成交 ID
     * @param cellIndex       格索引
     * @param exchangeOrderId 交易所訂單 ID
     * @param side            方向（"BUY"/"SELL"）
     * @param fillPrice       成交價格
     * @param fillQuantity    成交數量
     * @param realizedPnl     本次成交的已實現盈虧
     */
    public void recordTradeHistory(String tradeId, int cellIndex, String exchangeOrderId,
                                    String side, BigDecimal fillPrice, BigDecimal fillQuantity,
                                    BigDecimal realizedPnl) {
        checkInitialized();

        String sql = """
            INSERT INTO %s (trade_id, cell_index, exchange_order_id, side,
                            fill_price, fill_quantity, realized_pnl, trade_time_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(TABLE_TRADE_HISTORY);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, Objects.requireNonNull(tradeId, "tradeId 不能為 null"));
            pstmt.setInt(2, cellIndex);
            pstmt.setString(3, exchangeOrderId);
            pstmt.setString(4, Objects.requireNonNull(side, "side 不能為 null"));
            pstmt.setBigDecimal(5, Objects.requireNonNull(fillPrice, "fillPrice 不能為 null"));
            pstmt.setBigDecimal(6, Objects.requireNonNull(fillQuantity, "fillQuantity 不能為 null"));
            pstmt.setBigDecimal(7, Objects.requireNonNullElse(realizedPnl, BigDecimal.ZERO));
            pstmt.setLong(8, Instant.now().toEpochMilli());

            pstmt.executeUpdate();
            log.debug("成交記錄已保存 | cellIndex={} side={} price={} qty={} pnl={}",
                cellIndex, side, fillPrice, fillQuantity, realizedPnl);

        } catch (SQLException e) {
            // 成交記錄失敗不應中斷主流程，只記錄錯誤
            log.error("保存成交歷史失敗 | tradeId={}", tradeId, e);
        }
    }

    /**
     * 查詢累計已實現總盈虧。
     *
     * @return 總盈虧（USDT）；查詢失敗時返回 ZERO
     */
    public BigDecimal getTotalRealizedPnl() {
        checkInitialized();

        String sql = "SELECT COALESCE(SUM(realized_pnl), 0) FROM " + TABLE_TRADE_HISTORY;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                BigDecimal result = rs.getBigDecimal(1);
                return Objects.requireNonNullElse(result, BigDecimal.ZERO);
            }
            return BigDecimal.ZERO;

        } catch (SQLException e) {
            log.error("查詢總盈虧失敗", e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 查詢特定格索引的累計已實現盈虧。
     *
     * @param cellIndex 格索引
     * @return 該格累計盈虧（USDT）；若無或查詢失敗返回 ZERO
     */
    public BigDecimal getCellRealizedPnl(int cellIndex) {
        checkInitialized();

        String sql = "SELECT COALESCE(SUM(realized_pnl), 0) FROM " + TABLE_TRADE_HISTORY + " WHERE cell_index = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, cellIndex);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal result = rs.getBigDecimal(1);
                    return Objects.requireNonNullElse(result, BigDecimal.ZERO);
                }
            }
            return BigDecimal.ZERO;

        } catch (SQLException e) {
            log.error("查詢網格 Cell[{}] 總盈虧失敗", cellIndex, e);
            return BigDecimal.ZERO;
        }
    }

    // ============================================================
    // Bot 全局狀態持久化
    // ============================================================

    /**
     * 保存 Bot 全局狀態（重啟恢復用）。
     *
     * @param botStateStr   Bot 狀態字符串
     * @param totalRealizedPnl 累計已實現盈虧
     */
    public void saveBotState(String botStateStr, BigDecimal totalRealizedPnl) {
        checkInitialized();

        String sql = """
            MERGE INTO %s (id, bot_state, total_realized_pnl, last_update_ms)
            KEY (id)
            VALUES (1, ?, ?, ?)
            """.formatted(TABLE_BOT_STATE);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, Objects.requireNonNullElse(botStateStr, "INIT"));
            pstmt.setBigDecimal(2, Objects.requireNonNullElse(totalRealizedPnl, BigDecimal.ZERO));
            pstmt.setLong(3, Instant.now().toEpochMilli());
            pstmt.executeUpdate();

        } catch (SQLException e) {
            log.error("保存 Bot 狀態失敗", e);
        }
    }

    // ============================================================
    // 私有工具方法
    // ============================================================

    /**
     * 將 ResultSet 當前行映射為 OrderSnapshot 對象。
     * 所有欄位訪問均有防禦性空值處理。
     */
    private OrderSnapshot mapRowToSnapshot(ResultSet rs) {
        try {
            String snapshotId = rs.getString("snapshot_id");
            int cellIndex = rs.getInt("cell_index");
            String exchangeOrderId = rs.getString("exchange_order_id"); // 可為 null
            String side = rs.getString("side");
            BigDecimal price = rs.getBigDecimal("price");
            BigDecimal quantity = rs.getBigDecimal("quantity");
            BigDecimal filledQuantity = rs.getBigDecimal("filled_quantity");
            String status = rs.getString("status");
            long createdAtMs = rs.getLong("created_at_ms");
            long updatedAtMs = rs.getLong("updated_at_ms");

            // 防禦性空值處理
            if (snapshotId == null || side == null || price == null || quantity == null || status == null) {
                log.warn("快照記錄包含 null 必要欄位，跳過 cellIndex={}", cellIndex);
                return null;
            }

            return new OrderSnapshot(
                snapshotId, cellIndex, exchangeOrderId, side, price, quantity,
                Objects.requireNonNullElse(filledQuantity, BigDecimal.ZERO),
                status, createdAtMs, updatedAtMs
            );

        } catch (SQLException e) {
            log.error("解析快照記錄失敗", e);
            return null;
        }
    }

    /**
     * 確保 LocalStateManager 已初始化。
     */
    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("LocalStateManager 尚未初始化完成");
        }
    }

    // ============================================================
    // AutoCloseable：優雅關閉資源
    // ============================================================

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("關閉 HikariCP 連線池...");
            dataSource.close();
            log.info("LocalStateManager 已關閉");
        }
    }
}
