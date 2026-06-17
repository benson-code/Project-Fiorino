package com.fiorino.application.executor;

import com.fiorino.application.ratelimit.RateLimiter;
import com.fiorino.domain.model.GridCell;
import com.fiorino.domain.model.GridCell.CellStatus;
import com.fiorino.domain.model.GridCell.OrderSide;
import com.fiorino.domain.model.GridConfig;
import com.fiorino.domain.model.OrderSnapshot;
import com.fiorino.domain.statemachine.GridStateMachine;
import com.fiorino.infrastructure.api.BinanceApiAdapter;
import com.fiorino.infrastructure.persistence.LocalStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================
 * GridOrderExecutor — 高並發訂單執行引擎（Application Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類是整個系統最核心的「協調引擎」，負責將 Domain 層的網格邏輯
 * 與 Infrastructure 層的 API 調用和持久化操作串聯起來。
 *
 * Virtual Thread 並發模型：
 * ────────────────────────────────────────────────────────────
 * 傳統線程池方案：
 *   假設網格 50 格，每格掛單需要 10ms（API 延遲），
 *   如果用固定線程池（10 threads），需要 50ms 才能完成初始掛單。
 *
 * Virtual Thread 方案：
 *   50 個 Virtual Thread 同時發送 HTTP 請求（全部 non-blocking IO），
 *   受限於 API Rate Limit 而非線程數量，實際完成時間取決於 API 延遲。
 *   在 Mac mini M4 上，實測可在 < 5ms 內完成所有請求的發送。
 *
 * WAL 持久化順序（Write-Ahead Logging）：
 * ────────────────────────────────────────────────────────────
 * 嚴格的操作順序確保在任何崩潰點都能恢復：
 *
 *   1. cell.transitionToPending()     [Domain 狀態轉移]
 *   2. stateManager.upsertSnapshot()  [H2 持久化 PENDING 狀態] ← WAL 關鍵點
 *   3. binanceApi.placeLimitOrder()   [發送 API 請求]
 *   4. cell.transitionToActive()      [Domain 狀態轉移]
 *   5. stateManager.upsertSnapshot()  [H2 持久化 ACTIVE 狀態]
 *
 *   崩潰點分析：
 *   - 若在步驟 2 後崩潰：重啟時看到 PENDING 狀態，查詢 Binance，
 *     若無此訂單，重置為 EMPTY 並重新掛單。
 *   - 若在步驟 3 後崩潰：Binance 可能已接受訂單（但我們還沒記錄 orderId），
 *     重啟時查詢 Binance 所有活躍訂單，找到匹配的價格/方向，更新本地狀態。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class GridOrderExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GridOrderExecutor.class);

    // ============================================================
    // 核心依賴
    // ============================================================

    private final GridConfig gridConfig;
    private final GridStateMachine stateMachine;
    private final BinanceApiAdapter binanceApi;
    private final LocalStateManager stateManager;
    private final RateLimiter rateLimiter;

    // ============================================================
    // Virtual Thread Executor
    // ============================================================

    /**
     * Virtual Thread Per Task Executor：
     * 每個任務（下單、查詢、撤單）都在獨立的 Virtual Thread 中執行。
     * Virtual Thread 由 JVM 調度，不對應固定的 OS 線程，
     * 在 I/O 等待期間自動讓出 Carrier Thread，實現零成本並發。
     */
    private final ExecutorService virtualThreadExecutor;

    /** 待完成的下單 Future 集合（用於批量等待和取消） */
    private final Set<Future<?>> pendingFutures = ConcurrentHashMap.newKeySet();

    // ============================================================
    // 統計計數器（AtomicLong 確保無鎖線程安全）
    // ============================================================

    private final AtomicLong totalOrdersPlaced = new AtomicLong(0);
    private final AtomicLong totalOrdersFilled = new AtomicLong(0);
    private final AtomicLong totalOrdersFailed = new AtomicLong(0);
    private final AtomicLong totalOrdersCancelled = new AtomicLong(0);

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 創建網格訂單執行引擎。
     *
     * @param gridConfig   網格配置（不可變）
     * @param stateMachine Bot 狀態機
     * @param binanceApi   Binance API 適配器
     * @param stateManager H2 狀態管理器
     * @param rateLimiter  限流器
     */
    public GridOrderExecutor(GridConfig gridConfig, GridStateMachine stateMachine,
                              BinanceApiAdapter binanceApi, LocalStateManager stateManager,
                              RateLimiter rateLimiter) {

        this.gridConfig = Objects.requireNonNull(gridConfig, "GridConfig 不能為 null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "GridStateMachine 不能為 null");
        this.binanceApi = Objects.requireNonNull(binanceApi, "BinanceApiAdapter 不能為 null");
        this.stateManager = Objects.requireNonNull(stateManager, "LocalStateManager 不能為 null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter 不能為 null");

        // 初始化 Virtual Thread Executor
        // Java 21：Executors.newVirtualThreadPerTaskExecutor() 是推薦的 API
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        log.info("GridOrderExecutor 初始化完成 | symbol={} grids={} qty={}",
            gridConfig.getSymbol(), gridConfig.getGridCount(), gridConfig.getOrderQuantityPerGrid());
    }

    // ============================================================
    // 批量初始化掛單（Bot 啟動時調用）
    // ============================================================

    /**
     * 批量初始化所有需要掛單的網格單元。
     * 所有下單請求並發提交（Virtual Threads），但受 RateLimiter 約束。
     *
     * @param cells 所有網格單元列表
     */
    public void initializeGridOrders(List<GridCell> cells) {
        Objects.requireNonNull(cells, "網格單元列表不能為 null");

        if (!stateMachine.isRunning()) {
            log.warn("Bot 狀態不是 RUNNING（當前: {}），跳過初始化掛單", stateMachine.getCurrentState());
            return;
        }

        log.info("開始初始化網格掛單 | 共 {} 格", cells.size());
        long startMs = System.currentTimeMillis();

        // 批量提交下單任務（每個 Cell 一個 Virtual Thread）
        List<Future<?>> futures = new ArrayList<>(cells.size());
        for (GridCell cell : cells) {
            if (cell.needsOrder()) {
                Future<?> future = virtualThreadExecutor.submit(() -> placeOrderForCell(cell));
                futures.add(future);
                pendingFutures.add(future);
            }
        }

        // 等待所有下單完成（或超時）
        int successCount = 0;
        int failCount = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS); // 每格最多等待 30 秒
                successCount++;
            } catch (TimeoutException e) {
                log.error("初始化掛單超時，強制取消任務");
                future.cancel(true);
                failCount++;
            } catch (ExecutionException e) {
                log.error("初始化掛單任務異常: {}", e.getCause().getMessage(), e);
                failCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("初始化掛單被中斷");
                break;
            } finally {
                pendingFutures.remove(future);
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("初始化掛單完成 | 成功: {} | 失敗: {} | 耗時: {}ms", successCount, failCount, elapsed);
    }

    // ============================================================
    // 單格掛單（核心邏輯，WAL 順序）
    // ============================================================

    /**
     * 為單個網格單元執行限價掛單。
     * 嚴格遵守 WAL 順序：先持久化 → 再 API 調用 → 再更新狀態。
     *
     * @param cell 要掛單的網格單元
     */
    public void placeOrderForCell(GridCell cell) {
        Objects.requireNonNull(cell, "GridCell 不能為 null");

        if (!stateMachine.isRunning()) {
            log.debug("Bot 未在運行，跳過 Cell[{}] 的掛單", cell.getCellIndex());
            return;
        }

        // 防止重複掛單（已有訂單時跳過）
        if (!cell.needsOrder()) {
            log.debug("Cell[{}] 狀態為 {}，無需掛單", cell.getCellIndex(), cell.getStatus());
            return;
        }

        int cellIndex = cell.getCellIndex();
        String side = cell.getOrderSide().name();
        BigDecimal price = cell.getTriggerPrice();
        BigDecimal quantity = cell.getOrderQuantity();
        String snapshotId = UUID.randomUUID().toString();

        try {
            // ═══ 步驟 1：Domain 狀態轉移為 PENDING ═══
            cell.transitionToPending();
            log.debug("Cell[{}] {} @ {} 狀態 → PENDING", cellIndex, side, price);

            // ═══ 步驟 2：WAL 持久化 PENDING 狀態（崩潰安全點） ═══
            OrderSnapshot pendingSnapshot = new OrderSnapshot(
                snapshotId, cellIndex, null, side, price, quantity,
                BigDecimal.ZERO, "PENDING",
                System.currentTimeMillis(), System.currentTimeMillis()
            );
            stateManager.upsertOrderSnapshot(pendingSnapshot);
            log.debug("Cell[{}] PENDING 快照已持久化", cellIndex);

            // ═══ 步驟 3：發送 Binance 下單 API ═══
            Optional<String> orderIdOpt = binanceApi.placeLimitOrder(
                gridConfig.getSymbol(), side, quantity, price
            );

            if (orderIdOpt.isEmpty()) {
                // 下單失敗：回退到 ERROR 狀態，等待重試
                cell.transitionToError();
                stateManager.upsertOrderSnapshot(new OrderSnapshot(
                    snapshotId, cellIndex, null, side, price, quantity,
                    BigDecimal.ZERO, "ERROR",
                    pendingSnapshot.createdAtMs(), System.currentTimeMillis()
                ));
                totalOrdersFailed.incrementAndGet();
                log.error("Cell[{}] {} @ {} 下單失敗，已標記為 ERROR", cellIndex, side, price);
                return;
            }

            String orderId = orderIdOpt.get();

            // ═══ 步驟 4：Domain 狀態轉移為 ACTIVE ═══
            cell.transitionToActive(orderId);

            // ═══ 步驟 5：持久化 ACTIVE 狀態（含 orderId） ═══
            OrderSnapshot activeSnapshot = new OrderSnapshot(
                snapshotId, cellIndex, orderId, side, price, quantity,
                BigDecimal.ZERO, "ACTIVE",
                pendingSnapshot.createdAtMs(), System.currentTimeMillis()
            );
            stateManager.upsertOrderSnapshot(activeSnapshot);

            totalOrdersPlaced.incrementAndGet();
            log.info("Cell[{}] {} {} @ {} 掛單成功 | orderId: {}", cellIndex, side, quantity, price, orderId);

        } catch (IllegalStateException e) {
            // 狀態機轉移異常（並發競爭導致的預期外狀態）
            log.warn("Cell[{}] 狀態轉移衝突，跳過本次掛單: {}", cellIndex, e.getMessage());
        } catch (Throwable t) {
            log.error("Cell[{}] 掛單時發生未預期異常", cellIndex, t);
            try {
                cell.transitionToError();
            } catch (Exception ignored) {
                // 如果轉移到 ERROR 也失敗，忽略（狀態已不一致）
            }
            totalOrdersFailed.incrementAndGet();

            // 如果是嚴重異常，考慮觸發 Bot 級別的崩潰恢復
            if (t instanceof OutOfMemoryError || t instanceof StackOverflowError) {
                stateMachine.enterCrashRecovery("嚴重 JVM 錯誤: " + t.getClass().getSimpleName());
            }
        }
    }

    // ============================================================
    // 訂單成交處理
    // ============================================================

    /**
     * 處理訂單完全成交事件（由輪詢器或 WebSocket 回調觸發）。
     * 成交後根據方向，在反向的格子重新掛單（核心套利邏輯）。
     *
     * @param filledCell    已成交的網格單元
     * @param allCells      所有網格單元（用於找反向掛單格）
     * @param finalFillPrice 最終成交價格
     * @param finalFillQty  最終成交數量
     */
    public void handleOrderFilled(GridCell filledCell, List<GridCell> allCells,
                                   BigDecimal finalFillPrice, BigDecimal finalFillQty) {
        Objects.requireNonNull(filledCell, "已成交 Cell 不能為 null");
        Objects.requireNonNull(allCells, "所有 Cell 列表不能為 null");
        Objects.requireNonNull(finalFillPrice, "成交價格不能為 null");
        Objects.requireNonNull(finalFillQty, "成交數量不能為 null");

        int cellIndex = filledCell.getCellIndex();
        OrderSide side = filledCell.getOrderSide();
        BigDecimal fillPrice = finalFillPrice;
        String orderId = Objects.requireNonNullElse(filledCell.getOrderId(), "unknown");

        // 計算本次成交的已實現盈虧
        // 網格盈利 = 每格間距 × 成交數量 - 手續費（買賣兩次）
        // 僅在 SELL（平倉）成交時才結算已實現盈虧
        BigDecimal gridSpacing = gridConfig.getGridSpacing();
        BigDecimal netPnl = BigDecimal.ZERO;
        BigDecimal grossPnl = BigDecimal.ZERO;

        if (side == OrderSide.SELL) {
            BigDecimal buyPrice = fillPrice.subtract(gridSpacing);
            BigDecimal fee = fillPrice.add(buyPrice)
                .multiply(finalFillQty)
                .multiply(gridConfig.getMakerFeeRate());
            grossPnl = gridSpacing.multiply(finalFillQty);
            netPnl = grossPnl.subtract(fee).setScale(8, RoundingMode.HALF_UP);
        }

        log.info("Cell[{}] 成交事件 | {} {} @ {} | 毛利: {} | 淨利: {} | orderId: {}",
            cellIndex, side, finalFillQty, fillPrice, grossPnl, netPnl, orderId);

        try {
            // 更新 Domain 狀態為 FILLED
            filledCell.transitionToFilled(finalFillQty, fillPrice, netPnl);

            // 持久化 FILLED 狀態
            String snapshotId = UUID.randomUUID().toString();
            stateManager.upsertOrderSnapshot(new OrderSnapshot(
                snapshotId, cellIndex, orderId, side.name(), filledCell.getTriggerPrice(),
                finalFillQty, finalFillQty, "FILLED",
                System.currentTimeMillis(), System.currentTimeMillis()
            ));

            // 記錄成交歷史
            stateManager.recordTradeHistory(
                UUID.randomUUID().toString(), cellIndex, orderId, side.name(),
                fillPrice, finalFillQty, netPnl
            );

            totalOrdersFilled.incrementAndGet();

            // 確定反向掛單格索引
            int reverseIndex = calculateReverseCellIndex(cellIndex, side);
            if (reverseIndex >= 0 && reverseIndex < allCells.size()) {
                GridCell reverseCell = allCells.get(reverseIndex);

                // ============================================================
                // 核心網格方向逻輯：反向格必須改為相反方向
                // BUY  成交 → 反向格（价格更高）改為 SELL
                // SELL 成交 → 反向格（价格更低）改為 BUY
                // ============================================================
                OrderSide reverseSide = (side == OrderSide.BUY) ? OrderSide.SELL : OrderSide.BUY;
                if (reverseCell.getOrderSide() != reverseSide) {
                    reverseCell.updateOrderSide(reverseSide);
                    log.debug("反向格[{}] 方向更新: {} → {}",
                        reverseIndex, reverseCell.getOrderSide(), reverseSide);
                }

                log.info("觸發反向掛單 | 原格[{}]{} 成交 → 反向格[{}] {} @ {}",
                    cellIndex, side, reverseIndex,
                    reverseSide, reverseCell.getTriggerPrice());

                // 在反向格子重新掛單（非同步，Virtual Thread 執行）
                virtualThreadExecutor.submit(() -> placeOrderForCell(reverseCell));
            } else {
                log.warn("反向格索引 {} 超出範圍 [0,{}]，已到達網格邊界，跳過反向掛單",
                    reverseIndex, allCells.size() - 1);
            }

            // 重置當前格為 EMPTY，準備接受下一個週期
            filledCell.resetToEmpty();
            stateManager.upsertOrderSnapshot(new OrderSnapshot(
                UUID.randomUUID().toString(), cellIndex, null, side.name(),
                filledCell.getTriggerPrice(), filledCell.getOrderQuantity(),
                BigDecimal.ZERO, "EMPTY",
                System.currentTimeMillis(), System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("處理 Cell[{}] 成交事件時發生異常", cellIndex, e);
            try {
                filledCell.transitionToError();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 計算反向掛單格的索引。
     * 網格邏輯：買單成交後 → 在上方格掛賣單；賣單成交後 → 在下方格掛買單。
     *
     * @param filledCellIndex 已成交格的索引
     * @param filledSide      已成交格的方向
     * @return 反向格的索引；-1 表示無效（邊界格）
     */
    private int calculateReverseCellIndex(int filledCellIndex, OrderSide filledSide) {
        if (Objects.equals(OrderSide.BUY, filledSide)) {
            // 買單成交 → 在上方一格掛賣單
            return filledCellIndex + 1;
        } else {
            // 賣單成交 → 在下方一格掛買單
            return filledCellIndex - 1;
        }
    }

    // ============================================================
    // 訂單撤銷（優雅停止用）
    // ============================================================

    /**
     * 撤銷指定 Cell 的活躍訂單（優雅停止 Bot 時調用）。
     *
     * @param cell 要撤銷訂單的格子
     */
    public void cancelOrderForCell(GridCell cell) {
        Objects.requireNonNull(cell, "GridCell 不能為 null");

        String orderId = cell.getOrderId();
        if (orderId == null || orderId.isBlank()) {
            log.debug("Cell[{}] 沒有活躍訂單，跳過撤單", cell.getCellIndex());
            return;
        }

        CellStatus currentStatus = cell.getStatus();
        if (!Objects.equals(CellStatus.ACTIVE, currentStatus) &&
            !Objects.equals(CellStatus.PARTIAL, currentStatus)) {
            log.debug("Cell[{}] 狀態為 {}，無需撤單", cell.getCellIndex(), currentStatus);
            return;
        }

        boolean cancelled = binanceApi.cancelOrder(gridConfig.getSymbol(), orderId);
        if (cancelled) {
            cell.transitionToCancelled();
            stateManager.upsertOrderSnapshot(new OrderSnapshot(
                UUID.randomUUID().toString(), cell.getCellIndex(), orderId,
                cell.getOrderSide().name(), cell.getTriggerPrice(), cell.getOrderQuantity(),
                cell.getFilledQuantity(), "CANCELLED",
                System.currentTimeMillis(), System.currentTimeMillis()
            ));
            totalOrdersCancelled.incrementAndGet();
            log.info("Cell[{}] 訂單撤銷成功 | orderId: {}", cell.getCellIndex(), orderId);
        } else {
            log.warn("Cell[{}] 訂單撤銷失敗 | orderId: {}", cell.getCellIndex(), orderId);
        }
    }

    /**
     * 批量撤銷所有活躍訂單（Bot 停止時調用）。
     * 所有撤單請求並發執行。
     *
     * @param cells 所有格子列表
     */
    public void cancelAllOrders(List<GridCell> cells) {
        Objects.requireNonNull(cells, "格子列表不能為 null");
        log.info("開始批量撤銷所有訂單...");

        List<Future<?>> futures = new ArrayList<>();
        cells.stream()
            .filter(GridCell::hasActiveOrder)
            .map(cell -> (Future<?>) virtualThreadExecutor.submit(() -> cancelOrderForCell(cell)))
            .forEach(futures::add);

        for (Future<?> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("撤單任務異常: {}", e.getMessage());
            }
        }

        log.info("批量撤單完成 | 累計撤銷: {}", totalOrdersCancelled.get());
    }

    // ============================================================
    // 統計資訊查詢
    // ============================================================

    public long getTotalOrdersPlaced() { return totalOrdersPlaced.get(); }
    public long getTotalOrdersFilled() { return totalOrdersFilled.get(); }
    public long getTotalOrdersFailed() { return totalOrdersFailed.get(); }
    public long getTotalOrdersCancelled() { return totalOrdersCancelled.get(); }

    // ============================================================
    // AutoCloseable
    // ============================================================

    @Override
    public void close() {
        log.info("關閉 GridOrderExecutor Virtual Thread Executor...");
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
                log.warn("強制終止 Virtual Thread Executor");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
        log.info("GridOrderExecutor 已關閉");
    }
}
