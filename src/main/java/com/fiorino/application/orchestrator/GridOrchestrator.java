package com.fiorino.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fiorino.application.executor.GridOrderExecutor;
import com.fiorino.application.ratelimit.RateLimiter;
import com.fiorino.domain.model.GridCell;
import com.fiorino.domain.model.GridCell.CellStatus;
import com.fiorino.domain.model.GridCell.OrderSide;
import com.fiorino.domain.model.GridConfig;
import com.fiorino.domain.model.OrderSnapshot;
import com.fiorino.domain.statemachine.GridStateMachine;
import com.fiorino.infrastructure.api.BinanceApiAdapter;
import com.fiorino.infrastructure.dashboard.ConsoleDashboard;
import com.fiorino.infrastructure.persistence.LocalStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class GridOrchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GridOrchestrator.class);

    private static final long ORDER_POLL_INTERVAL_MS = 3_000L;
    private static final long SERVER_TIME_SYNC_INTERVAL_MS = 300_000L;
    private static final long BALANCE_UPDATE_INTERVAL_MS = 30_000L;

    private final GridConfig gridConfig;
    private final GridStateMachine stateMachine;
    private final BinanceApiAdapter binanceApi;
    private final LocalStateManager stateManager;
    private final GridOrderExecutor orderExecutor;
    private final RateLimiter rateLimiter;

    private volatile ConsoleDashboard dashboard;
    private final List<GridCell> gridCells;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pollFuture;
    private volatile ScheduledFuture<?> timeSyncFuture;
    private volatile ScheduledFuture<?> errorRecoveryFuture;
    private volatile long lastSuccessfulPollMs = 0L;
    private final AtomicReference<BigDecimal> lastKnownPrice = new AtomicReference<>(BigDecimal.ZERO);
    private volatile long lastBalanceUpdateMs = 0L;

    /** 對外共享的價格引用（Dashboard 顯示用） */
    private volatile AtomicReference<BigDecimal> externalPriceRef;

    public GridOrchestrator(GridConfig gridConfig, GridStateMachine stateMachine,
                             BinanceApiAdapter binanceApi, LocalStateManager stateManager,
                             GridOrderExecutor orderExecutor, RateLimiter rateLimiter) {

        this.gridConfig = Objects.requireNonNull(gridConfig, "GridConfig cannot be null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "GridStateMachine cannot be null");
        this.binanceApi = Objects.requireNonNull(binanceApi, "BinanceApiAdapter cannot be null");
        this.stateManager = Objects.requireNonNull(stateManager, "LocalStateManager cannot be null");
        this.orderExecutor = Objects.requireNonNull(orderExecutor, "GridOrderExecutor cannot be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter cannot be null");

        this.gridCells = buildGridCells(BigDecimal.ZERO);
        log.info("Grid cells built | count={} | range=[{},{}] | spacing={}",
            gridCells.size(), gridConfig.getLowerPrice(), gridConfig.getUpperPrice(),
            gridConfig.getGridSpacing());

        this.scheduler = Executors.newScheduledThreadPool(
            2,
            Thread.ofVirtual().name("fiorino-scheduler-", 0).factory()
        );
    }

    public void setDashboard(ConsoleDashboard dashboard) {
        this.dashboard = dashboard;
        log.info("ConsoleDashboard connected to GridOrchestrator");
    }

    /**
     * 設定對外共享的價格引用（Dashboard 實時顯示用）。
     * 在 Main.java 中建立後紜就設定。
     */
    public void setExternalPriceRef(AtomicReference<BigDecimal> priceRef) {
        this.externalPriceRef = priceRef;
    }

    public void start() throws Exception {
        log.info("=== Project Fiorino Bot Starting ===");

        log.info("[1/6] Syncing Binance server time...");
        binanceApi.syncServerTime();

        log.info("[2/6] Testing API connectivity...");
        long pingMs = binanceApi.ping();
        if (pingMs < 0) {
            throw new RuntimeException("Cannot connect to Binance API, check network");
        }
        log.info("[2/6] API ping OK | latency={}ms", pingMs);

        log.info("[3/6] Getting current {} market price...", gridConfig.getSymbol());
        BigDecimal initialMarketPrice = binanceApi.getCurrentPrice(gridConfig.getSymbol())
            .orElseThrow(() -> new RuntimeException(
                "Cannot get market price for " + gridConfig.getSymbol()));
        lastKnownPrice.set(initialMarketPrice);
        log.info("[3/6] Market price: {} USDT - initializing grid directions", initialMarketPrice);

        rebuildGridCellsWithMarketPrice(initialMarketPrice);

        log.info("[4/6] Restoring from local snapshot...");
        restoreFromLocalSnapshot();

        log.info("[5/6] Reconciling with Binance open orders...");
        reconcileWithBinance();

        log.info("[6/6] Starting grid strategy...");
        stateMachine.start();
        orderExecutor.initializeGridOrders(gridCells);
        startBackgroundTasks();

        if (dashboard != null) {
            try {
                Map<String, BigDecimal> balances = binanceApi.getAccountBalances();
                BigDecimal btc = balances.getOrDefault("BTC", BigDecimal.ZERO);
                BigDecimal usdt = balances.getOrDefault("USDT", BigDecimal.ZERO);
                dashboard.updateBalances(btc, usdt);
                lastBalanceUpdateMs = System.currentTimeMillis();
                log.info("Initial balance: BTC={} | USDT={}", btc, usdt);
            } catch (Exception e) {
                log.warn("Initial balance query failed: {}", e.getMessage());
            }
        }

        log.info("=== Project Fiorino Bot Started Successfully ===");
    }

    public void stop() {
        log.info("Bot stopping gracefully...");
        stateMachine.stop("User requested stop");
        stopBackgroundTasks();
        log.info("Cancelling all active orders...");
        orderExecutor.cancelAllOrders(gridCells);
        log.info("Persisting final state...");
        BigDecimal totalPnl = stateManager.getTotalRealizedPnl();
        stateManager.saveBotState("STOPPED", totalPnl);
        log.info("Bot stopped | total realized PnL: {} USDT", totalPnl);
    }

    private void rebuildGridCellsWithMarketPrice(BigDecimal marketPrice) {
        if (marketPrice == null || marketPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid market price ({}), keeping default BUY direction", marketPrice);
            return;
        }

        int buyCount = 0;
        int sellCount = 0;

        for (GridCell cell : gridCells) {
            OrderSide newSide;
            if (cell.getTriggerPrice().compareTo(marketPrice) < 0) {
                newSide = OrderSide.BUY;
                buyCount++;
            } else {
                newSide = OrderSide.SELL;
                sellCount++;
            }
            cell.updateOrderSide(newSide);
        }

        log.info("Grid directions set | marketPrice={} | BUY cells={} | SELL cells={}",
            marketPrice, buyCount, sellCount);
    }

    private void restoreFromLocalSnapshot() {
        // 先還原所有格子的歷史已實現盈虧
        for (GridCell cell : gridCells) {
            BigDecimal pnl = stateManager.getCellRealizedPnl(cell.getCellIndex());
            if (pnl.compareTo(BigDecimal.ZERO) != 0) {
                cell.initRealizedPnl(pnl);
                log.debug("Cell[{}] 還原歷史已實現盈虧: {} USDT", cell.getCellIndex(), pnl);
            }
        }

        List<OrderSnapshot> snapshots = stateManager.loadAllSnapshots();

        if (snapshots.isEmpty()) {
            log.info("No local snapshot found, starting fresh");
            return;
        }

        log.info("Found {} local snapshots, restoring...", snapshots.size());

        int restoredCount = 0;
        for (OrderSnapshot snapshot : snapshots) {
            int cellIndex = snapshot.cellIndex();
            if (cellIndex < 0 || cellIndex >= gridCells.size()) {
                log.warn("Snapshot cellIndex={} out of range [0,{}], skipping",
                    cellIndex, gridCells.size() - 1);
                continue;
            }

            GridCell cell = gridCells.get(cellIndex);
            String statusStr = snapshot.status();

            try {
                switch (Objects.requireNonNullElse(statusStr, "EMPTY")) {
                    case "ACTIVE", "PARTIAL" -> {
                        if (snapshot.exchangeOrderId() != null) {
                            cell.transitionToPending();
                            cell.transitionToActive(snapshot.exchangeOrderId());
                            restoredCount++;
                            log.debug("Restored Cell[{}] to ACTIVE | orderId: {}",
                                cellIndex, snapshot.exchangeOrderId());
                        }
                    }
                    case "PENDING" -> {
                        cell.transitionToPending();
                        restoredCount++;
                        log.debug("Restored Cell[{}] to PENDING (pending reconciliation)", cellIndex);
                    }
                    default -> { /* EMPTY/FILLED/CANCELLED: keep EMPTY */ }
                }
            } catch (Exception e) {
                log.warn("Error restoring Cell[{}] state: {}", cellIndex, e.getMessage());
            }
        }

        log.info("Snapshot restoration complete | restored cells: {}", restoredCount);
    }

    private void reconcileWithBinance() {
        log.info("Starting Binance order reconciliation...");

        List<JsonNode> binanceOrders = binanceApi.getAllOpenOrders(gridConfig.getSymbol());
        log.info("Binance open orders: {}", binanceOrders.size());

        Map<String, JsonNode> binanceOrderMap = new HashMap<>();
        for (JsonNode order : binanceOrders) {
            if (order == null || order.isNull()) continue;
            JsonNode orderIdNode = order.path("orderId");
            if (!orderIdNode.isMissingNode() && !orderIdNode.isNull()) {
                binanceOrderMap.put(String.valueOf(orderIdNode.asLong()), order);
            }
        }

        for (GridCell cell : gridCells) {
            CellStatus status = cell.getStatus();
            String orderId = cell.getOrderId();
            int cellIndex = cell.getCellIndex();

            if (Objects.equals(CellStatus.EMPTY, status) ||
                Objects.equals(CellStatus.CANCELLED, status)) {
                continue;
            }

            if (orderId == null || orderId.isBlank()) {
                log.warn("Cell[{}] status={} but no orderId, resetting to EMPTY", cellIndex, status);
                cell.resetToEmpty();
                continue;
            }

            if (binanceOrderMap.containsKey(orderId)) {
                JsonNode binanceOrder = binanceOrderMap.get(orderId);
                JsonNode executedQtyNode = binanceOrder.path("executedQty");
                if (!executedQtyNode.isMissingNode() && !executedQtyNode.isNull()) {
                    try {
                        BigDecimal executedQty = new BigDecimal(executedQtyNode.asText("0"));
                        if (executedQty.compareTo(BigDecimal.ZERO) > 0 &&
                            executedQty.compareTo(cell.getFilledQuantity()) > 0) {
                            cell.updatePartialFill(executedQty, cell.getTriggerPrice());
                            log.info("Cell[{}] reconciled fill qty: {}", cellIndex, executedQty);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Cell[{}] failed to parse executedQty", cellIndex);
                    }
                }
            } else {
                log.warn("Cell[{}] local status={} but Binance has no orderId={}, resetting",
                    cellIndex, status, orderId);
                cell.resetToEmpty();
            }
        }

        log.info("Binance reconciliation complete");
    }

    private void startBackgroundTasks() {
        pollFuture = scheduler.scheduleWithFixedDelay(
            this::pollOrderStatuses,
            ORDER_POLL_INTERVAL_MS,
            ORDER_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        timeSyncFuture = scheduler.scheduleWithFixedDelay(
            this::syncServerTime,
            SERVER_TIME_SYNC_INTERVAL_MS,
            SERVER_TIME_SYNC_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        // 新增：每 30 秒自動掃描並修復進入 ERROR 狀態的死格
        errorRecoveryFuture = scheduler.scheduleWithFixedDelay(
            this::recoverErrorGridCells,
            30_000L,
            30_000L,
            TimeUnit.MILLISECONDS
        );

        log.info("Background tasks started | poll={}ms | timeSync={}ms | errorRecovery=30000ms",
            ORDER_POLL_INTERVAL_MS, SERVER_TIME_SYNC_INTERVAL_MS);
    }

    private void stopBackgroundTasks() {
        if (pollFuture != null) { pollFuture.cancel(false); }
        if (timeSyncFuture != null) { timeSyncFuture.cancel(false); }
        if (errorRecoveryFuture != null) { errorRecoveryFuture.cancel(false); }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void pollOrderStatuses() {
        if (!stateMachine.isRunning()) return;

        try {
            Optional<BigDecimal> priceOpt = binanceApi.getCurrentPrice(gridConfig.getSymbol());
            priceOpt.ifPresent(price -> {
                lastKnownPrice.set(price);
                // 同步更新對外共享的價格引用（Dashboard 顯示）
                if (externalPriceRef != null) {
                    externalPriceRef.set(price);
                }
            });

            long now = System.currentTimeMillis();
            if (dashboard != null && (now - lastBalanceUpdateMs) >= BALANCE_UPDATE_INTERVAL_MS) {
                lastBalanceUpdateMs = now;
                try {
                    Map<String, BigDecimal> balances = binanceApi.getAccountBalances();
                    BigDecimal btc = balances.getOrDefault("BTC", BigDecimal.ZERO);
                    BigDecimal usdt = balances.getOrDefault("USDT", BigDecimal.ZERO);
                    dashboard.updateBalances(btc, usdt);
                    log.debug("Balance updated | BTC: {} | USDT: {}", btc, usdt);
                } catch (Exception e) {
                    log.warn("Balance update failed: {}", e.getMessage());
                }
            }

            int filledCount = 0;

            for (GridCell cell : gridCells) {
                if (!cell.hasActiveOrder()) continue;

                String orderId = cell.getOrderId();
                if (orderId == null || orderId.isBlank()) continue;

                Optional<JsonNode> orderOpt = binanceApi.getOrderStatus(
                    gridConfig.getSymbol(), orderId);

                if (orderOpt.isEmpty()) {
                    log.warn("Cell[{}] order status query failed (possible network issue)",
                        cell.getCellIndex());
                    continue;
                }

                JsonNode order = orderOpt.get();
                String binanceStatus = order.path("status").asText("");

                switch (binanceStatus) {
                    case "FILLED" -> {
                        BigDecimal fillPrice = parseBigDecimalSafe(order.path("price").asText("0"));
                        BigDecimal fillQty = parseBigDecimalSafe(order.path("executedQty").asText("0"));

                        String cummulativeQuoteQty = order.path("cummulativeQuoteQty").asText(null);
                        String executedQty = order.path("executedQty").asText(null);
                        if (cummulativeQuoteQty != null && executedQty != null) {
                            try {
                                BigDecimal totalQuote = new BigDecimal(cummulativeQuoteQty);
                                BigDecimal totalBase = new BigDecimal(executedQty);
                                if (totalBase.compareTo(BigDecimal.ZERO) > 0) {
                                    fillPrice = totalQuote.divide(totalBase, 8, java.math.RoundingMode.HALF_UP);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to compute avg fill price, using order price");
                            }
                        }

                        orderExecutor.handleOrderFilled(cell, gridCells, fillPrice, fillQty);
                        filledCount++;
                        log.info("Cell[{}] order FILLED | price={} qty={}",
                            cell.getCellIndex(), fillPrice, fillQty);
                    }
                    case "PARTIALLY_FILLED" -> {
                        BigDecimal filledQty = parseBigDecimalSafe(order.path("executedQty").asText("0"));
                        BigDecimal avgPrice = parseBigDecimalSafe(order.path("price").asText("0"));
                        cell.updatePartialFill(filledQty, avgPrice);
                        log.debug("Cell[{}] partially filled qty={}", cell.getCellIndex(), filledQty);
                    }
                    case "CANCELED" -> {
                        log.warn("Cell[{}] 訂單已撤銷 (CANCELED) | orderId: {}，重置並重新掛單",
                            cell.getCellIndex(), orderId);
                        cell.resetToEmpty();
                        orderExecutor.placeOrderForCell(cell);
                    }
                    case "EXPIRED", "REJECTED" -> {
                        log.error("Cell[{}] 訂單狀態異常 ({}): {} | orderId: {}，進入 ERROR 狀態避免無限重試",
                            cell.getCellIndex(), binanceStatus, orderId);
                        cell.transitionToError();
                        stateManager.upsertOrderSnapshot(new OrderSnapshot(
                            UUID.randomUUID().toString(), cell.getCellIndex(), orderId,
                            cell.getOrderSide().name(), cell.getTriggerPrice(), cell.getOrderQuantity(),
                            cell.getFilledQuantity(), "ERROR",
                            System.currentTimeMillis(), System.currentTimeMillis()
                        ));
                    }
                    default -> {
                        log.trace("Cell[{}] order status: {}", cell.getCellIndex(), binanceStatus);
                    }
                }
            }

            lastSuccessfulPollMs = System.currentTimeMillis();

            if (filledCount > 0) {
                log.info("Poll cycle: {} fills detected", filledCount);
            }

        } catch (Exception e) {
            log.error("Order polling exception", e);

            long timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulPollMs;
            if (lastSuccessfulPollMs > 0 && timeSinceLastSuccess > 60_000L) {
                log.error("No successful poll for >60s, triggering crash recovery");
                stateMachine.enterCrashRecovery("Poll failed >60s: " + e.getMessage());
            }
        }
    }

    private void syncServerTime() {
        try {
            binanceApi.syncServerTime();
        } catch (Exception e) {
            log.warn("Server time sync failed (background): {}", e.getMessage());
        }
    }

    /**
     * 自動修復處於 ERROR 狀態的網格單元，將其重置並重新掛單。
     */
    private void recoverErrorGridCells() {
        if (!stateMachine.isRunning()) return;

        try {
            int recoveredCount = 0;
            for (GridCell cell : gridCells) {
                if (Objects.equals(GridCell.CellStatus.ERROR, cell.getStatus())) {
                    log.info("發現處於 ERROR 狀態的網格 Cell[{}]，嘗試自動修復並重新掛單...", cell.getCellIndex());
                    cell.resetToEmpty();
                    
                    // 使用 Virtual Thread 提交掛單任務，防阻塞修復線程
                    orderExecutor.placeOrderForCell(cell);
                    recoveredCount++;
                }
            }
            if (recoveredCount > 0) {
                log.info("已完成 {} 個 ERROR 網格的自動修復掛單", recoveredCount);
            }
        } catch (Exception e) {
            log.error("自動修復 ERROR 網格時發生未預期異常", e);
        }
    }

    private List<GridCell> buildGridCells(BigDecimal marketPrice) {
        int gridCount = gridConfig.getGridCount();
        List<GridCell> cells = new ArrayList<>(gridCount);

        for (int i = 0; i < gridCount; i++) {
            BigDecimal price = gridConfig.priceAtCell(i);
            BigDecimal qty = gridConfig.getOrderQuantityPerGrid();

            OrderSide side;
            if (marketPrice != null && marketPrice.compareTo(BigDecimal.ZERO) > 0) {
                side = price.compareTo(marketPrice) < 0 ? OrderSide.BUY : OrderSide.SELL;
            } else {
                side = OrderSide.BUY;
            }

            cells.add(new GridCell(i, price, qty, side));
        }

        return Collections.unmodifiableList(cells);
    }

    private BigDecimal parseBigDecimalSafe(String str) {
        if (str == null || str.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(str);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public List<GridCell> getGridCells() { return gridCells; }

    public BigDecimal getLastKnownPrice() {
        return Objects.requireNonNullElse(lastKnownPrice.get(), BigDecimal.ZERO);
    }

    public long getLastSuccessfulPollMs() { return lastSuccessfulPollMs; }

    @Override
    public void close() { stop(); }
}
