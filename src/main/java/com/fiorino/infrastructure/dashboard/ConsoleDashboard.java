package com.fiorino.infrastructure.dashboard;

import com.fiorino.application.executor.GridOrderExecutor;
import com.fiorino.application.ratelimit.RateLimiter;
import com.fiorino.domain.model.GridCell;
import com.fiorino.domain.model.GridCell.CellStatus;
import com.fiorino.domain.model.GridConfig;
import com.fiorino.domain.statemachine.GridStateMachine;
import com.fiorino.infrastructure.api.BinanceApiAdapter;
import com.fiorino.infrastructure.api.CoinGlassApiAdapter;
import com.fiorino.infrastructure.persistence.LocalStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ============================================================
 * ConsoleDashboard — ASCII TUI 控制台監控面板（Infrastructure Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類採用「觀察者模式」的變體：Dashboard 不修改任何狀態，
 * 只「讀取」各組件的當前狀態並渲染成 ASCII 介面。
 *
 * 設計原則：
 * 1. 只讀無副作用（Pure Observer）：Dashboard 線程不持有任何業務鎖，
 *    所有讀取操作均透過 GridCell 的「樂觀讀（Optimistic Read）」完成。
 *
 * 2. 獨立 Virtual Thread（不干擾業務線程）：
 *    Dashboard 在獨立的 Virtual Thread 中每秒刷新一次，
 *    即使 Dashboard 刷新失敗（例如 ANSI 碼不支援），也不影響交易邏輯。
 *
 * 3. ANSI 轉義碼（ANSI Escape Codes）：
 *    使用 \033[2J（清屏）和 \033[H（回到左上角）實現原地刷新。
 *    在 Mac 終端機（iTerm2, Terminal.app）上完美支援。
 *    在非 ANSI 終端環境下優雅降級（只輸出文字，不清屏）。
 *
 * 性能設計：
 * - 每次刷新先建立整個 Dashboard 字串（StringBuilder），
 *   再一次性 flush 到 STDOUT。避免多次 System.out.println 造成的
 *   輸出撕裂（Tearing）現象。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class ConsoleDashboard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsoleDashboard.class);

    // ============================================================
    // ANSI 色彩碼（美化 TUI 介面）
    // ============================================================

    private static final String ANSI_RESET   = "\033[0m";
    private static final String ANSI_BOLD    = "\033[1m";
    private static final String ANSI_BLACK   = "\033[30m";
    private static final String ANSI_RED     = "\033[31m";
    private static final String ANSI_GREEN   = "\033[32m";
    private static final String ANSI_YELLOW  = "\033[33m";
    private static final String ANSI_BLUE    = "\033[34m";
    private static final String ANSI_MAGENTA = "\033[35m";
    private static final String ANSI_CYAN    = "\033[36m";
    private static final String ANSI_WHITE   = "\033[37m";

    // 背景色
    private static final String ANSI_BG_BLACK  = "\033[40m";
    private static final String ANSI_BG_RED    = "\033[41m";
    private static final String ANSI_BG_GREEN  = "\033[42m";
    private static final String ANSI_BG_YELLOW = "\033[43m";
    private static final String ANSI_BG_BLUE   = "\033[44m";

    // ANSI 清屏和游標控制
    private static final String ANSI_CLEAR_SCREEN = "\033[2J\033[H";
    private static final String ANSI_HIDE_CURSOR  = "\033[?25l";
    private static final String ANSI_SHOW_CURSOR  = "\033[?25h";

    // ============================================================
    // Dashboard 寬度常數
    // ============================================================

    private static final int DASHBOARD_WIDTH = 70;

    // ============================================================
    // 刷新配置
    // ============================================================

    private static final long REFRESH_INTERVAL_MS = 1_000L; // 每秒刷新一次

    // ============================================================
    // 核心依賴（只讀引用）
    // ============================================================

    private final GridConfig gridConfig;
    private final GridStateMachine stateMachine;
    private final List<GridCell> gridCells;
    private final BinanceApiAdapter binanceApi;
    private final LocalStateManager stateManager;
    private final GridOrderExecutor orderExecutor;
    private final RateLimiter rateLimiter;

    /** CoinGlass BTC 市場分析適配器 */
    private final CoinGlassApiAdapter coinGlassApi;

    // ============================================================
    // 外部注入的動態數據（由 Orchestrator 更新）
    // ============================================================

    /** 當前 BTC 市場價格（原子引用，線程安全） */
    private final AtomicReference<BigDecimal> currentBtcPrice;

    /** 當前 BTC 餘額 */
    private volatile BigDecimal btcBalance = BigDecimal.ZERO;

    /** 當前 USDT 餘額 */
    private volatile BigDecimal usdtBalance = BigDecimal.ZERO;

    // ============================================================
    // 後台刷新任務
    // ============================================================

    private final ScheduledExecutorService refreshScheduler;
    private volatile ScheduledFuture<?> refreshFuture;
    private volatile boolean running = false;

    /** Bot 啟動時間（計算運行時長） */
    private final Instant startTime;

    /** 日期格式化器 */
    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 創建 ConsoleDashboard。
     *
     * @param gridConfig      網格配置
     * @param stateMachine    Bot 狀態機
     * @param gridCells       所有網格格子
     * @param binanceApi      Binance API 適配器
     * @param stateManager    狀態管理器
     * @param orderExecutor   訂單執行引擎
     * @param rateLimiter     限流器
     * @param currentBtcPrice 當前 BTC 價格（由外部更新）
     * @param coinGlassApi    CoinGlass BTC 市場分析適配器
     */
    public ConsoleDashboard(GridConfig gridConfig, GridStateMachine stateMachine,
                             List<GridCell> gridCells, BinanceApiAdapter binanceApi,
                             LocalStateManager stateManager, GridOrderExecutor orderExecutor,
                             RateLimiter rateLimiter, AtomicReference<BigDecimal> currentBtcPrice,
                             CoinGlassApiAdapter coinGlassApi) {

        this.gridConfig = Objects.requireNonNull(gridConfig, "GridConfig 不能為 null");
        this.stateMachine = Objects.requireNonNull(stateMachine, "GridStateMachine 不能為 null");
        this.gridCells = Objects.requireNonNull(gridCells, "網格格子列表不能為 null");
        this.binanceApi = Objects.requireNonNull(binanceApi, "BinanceApiAdapter 不能為 null");
        this.stateManager = Objects.requireNonNull(stateManager, "LocalStateManager 不能為 null");
        this.orderExecutor = Objects.requireNonNull(orderExecutor, "GridOrderExecutor 不能為 null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "RateLimiter 不能為 null");
        this.currentBtcPrice = Objects.requireNonNull(currentBtcPrice, "currentBtcPrice 不能為 null");
        this.coinGlassApi = Objects.requireNonNull(coinGlassApi, "CoinGlassApiAdapter 不能為 null");

        this.startTime = Instant.now();

        // 使用 Virtual Thread 作為刷新線程
        this.refreshScheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("fiorino-dashboard").factory()
        );
    }

    // ============================================================
    // 生命週期管理
    // ============================================================

    /**
     * 啟動 Dashboard 刷新循環。
     * 在獨立的 Virtual Thread 中每秒刷新一次。
     */
    public void start() {
        if (running) {
            log.warn("Dashboard 已在運行中");
            return;
        }
        running = true;

        // 隱藏光標（減少閃爍）
        System.out.print(ANSI_HIDE_CURSOR);
        System.out.flush();

        refreshFuture = refreshScheduler.scheduleWithFixedDelay(
            this::refresh,
            0,
            REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        log.info("ConsoleDashboard 已啟動，刷新間隔: {}ms", REFRESH_INTERVAL_MS);
    }

    /**
     * 停止 Dashboard 刷新。
     */
    public void stop() {
        running = false;
        if (refreshFuture != null) {
            refreshFuture.cancel(false);
        }
        refreshScheduler.shutdown();

        // 恢復光標顯示
        System.out.print(ANSI_SHOW_CURSOR);
        System.out.println();
        log.info("ConsoleDashboard 已停止");
    }

    /**
     * 更新資產餘額數據（由外部定期調用）。
     *
     * @param btcBalance  BTC 可用餘額
     * @param usdtBalance USDT 可用餘額
     */
    public void updateBalances(BigDecimal btcBalance, BigDecimal usdtBalance) {
        this.btcBalance = Objects.requireNonNullElse(btcBalance, BigDecimal.ZERO);
        this.usdtBalance = Objects.requireNonNullElse(usdtBalance, BigDecimal.ZERO);
    }

    // ============================================================
    // Dashboard 渲染核心
    // ============================================================

    /**
     * 執行一次 Dashboard 刷新。
     * 先建立完整字串，再一次性輸出，避免螢幕撕裂。
     */
    private void refresh() {
        if (!running) return;

        try {
            StringBuilder sb = new StringBuilder(4096);

            // 清屏並回到左上角
            sb.append(ANSI_CLEAR_SCREEN);

            // ═══ 標題欄 ═══
            renderHeader(sb);

            // ═══ 系統狀態 ═══
            renderSystemStatus(sb);

            // ═══ 資產與盈虧 ═══
            renderBalanceAndPnl(sb);

            // ═══ 網格統計 ═══
            renderGridStatistics(sb);

            // ═══ API 狀態 ═══
            renderApiStatus(sb);

            // ═══ CoinGlass BTC 市場分析 ═══
            renderCoinGlassPanel(sb);

            // ═══ 網格格子狀態（最多顯示 20 格） ═══
            renderGridCells(sb);

            // ═══ 頁腳 ═══
            renderFooter(sb);

            // 一次性輸出所有內容（原子操作，避免撕裂）
            System.out.print(sb);
            System.out.flush();

        } catch (Exception e) {
            // Dashboard 渲染失敗不應影響交易邏輯
            log.debug("Dashboard 刷新時發生異常（不影響交易）: {}", e.getMessage());
        }
    }

    // ============================================================
    // 各區塊渲染方法
    // ============================================================

    /**
     * 渲染標題欄。
     */
    private void renderHeader(StringBuilder sb) {
        String title = "  ₿  PROJECT FIORINO — BTC GRID TRADING BOT  ";
        sb.append(ANSI_BG_BLUE).append(ANSI_BOLD).append(ANSI_WHITE);
        sb.append(centerPad(title, DASHBOARD_WIDTH));
        sb.append(ANSI_RESET).append('\n');

        // 時間顯示
        String timeStr = LocalDateTime.now(ZoneId.systemDefault()).format(TIME_FORMATTER);
        String uptime = formatUptime();
        sb.append(ANSI_CYAN)
          .append(String.format("  🕐 %-30s 🚀 Uptime: %s%n", timeStr, uptime))
          .append(ANSI_RESET);
        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染系統狀態欄。
     */
    private void renderSystemStatus(StringBuilder sb) {
        var botState = stateMachine.getCurrentState();
        String stateColor = switch (botState) {
            case RUNNING -> ANSI_GREEN;
            case PAUSED -> ANSI_YELLOW;
            case CRASHED_RECOVERING -> ANSI_RED;
            case STOPPED -> ANSI_RED;
            default -> ANSI_WHITE;
        };

        String stateIcon = switch (botState) {
            case RUNNING -> "✅";
            case PAUSED -> "⏸️ ";
            case CRASHED_RECOVERING -> "🔄";
            case STOPPED -> "🛑";
            default -> "⏳";
        };

        sb.append(ANSI_BOLD).append("  系統狀態").append(ANSI_RESET).append('\n');
        sb.append(String.format("  Bot 狀態:    %s%s %s%s%n",
            stateColor, ANSI_BOLD, stateIcon + " " + botState.name(), ANSI_RESET));

        String lastCrash = stateMachine.getLastCrashReason();
        int crashCount = stateMachine.getCrashCount();
        if (crashCount > 0) {
            sb.append(ANSI_YELLOW);
            sb.append(String.format("  崩潰次數:   %d | 最後原因: %s%n",
                crashCount, Objects.requireNonNullElse(lastCrash, "N/A")));
            sb.append(ANSI_RESET);
        }

        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染資產餘額與盈虧。
     */
    private void renderBalanceAndPnl(StringBuilder sb) {
        BigDecimal currentPrice = Objects.requireNonNullElse(currentBtcPrice.get(), BigDecimal.ZERO);
        BigDecimal btc = Objects.requireNonNullElse(btcBalance, BigDecimal.ZERO);
        BigDecimal usdt = Objects.requireNonNullElse(usdtBalance, BigDecimal.ZERO);

        // 計算未實現盈虧（所有 ACTIVE 格子的持倉價值差）
        BigDecimal unrealizedPnl = calculateUnrealizedPnl(currentPrice);
        BigDecimal realizedPnl = stateManager.getTotalRealizedPnl();

        sb.append(ANSI_BOLD).append("  💰 資產餘額").append(ANSI_RESET).append('\n');
        sb.append(String.format("  BTC 餘額:    %s%.6f BTC%s%n", ANSI_YELLOW, btc, ANSI_RESET));
        sb.append(String.format("  USDT 餘額:   %s%.2f USDT%s%n", ANSI_YELLOW, usdt, ANSI_RESET));
        sb.append(String.format("  BTC 現價:    %s%.2f USDT%s%n", ANSI_CYAN, currentPrice, ANSI_RESET));

        sb.append('\n');
        sb.append(ANSI_BOLD).append("  📊 盈虧統計").append(ANSI_RESET).append('\n');

        // 未實現盈虧
        String unrealizedColor = unrealizedPnl.compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED;
        String unrealizedSign = unrealizedPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        sb.append(String.format("  未實現盈虧:  %s%s%.4f USDT%s%n",
            unrealizedColor, unrealizedSign, unrealizedPnl, ANSI_RESET));

        // 已實現盈虧
        String realizedColor = realizedPnl.compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED;
        String realizedSign = realizedPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        sb.append(String.format("  已實現盈虧:  %s%s%.4f USDT%s%n",
            realizedColor, realizedSign, realizedPnl, ANSI_RESET));

        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染網格統計。
     */
    private void renderGridStatistics(StringBuilder sb) {
        // 統計各狀態的格子數量
        Map<CellStatus, Integer> statusCount = new EnumMap<>(CellStatus.class);
        for (CellStatus s : CellStatus.values()) {
            statusCount.put(s, 0);
        }
        for (GridCell cell : gridCells) {
            CellStatus s = cell.getStatus();
            statusCount.merge(s, 1, Integer::sum);
        }

        int activeOrders = Objects.requireNonNullElse(statusCount.get(CellStatus.ACTIVE), 0) +
                           Objects.requireNonNullElse(statusCount.get(CellStatus.PARTIAL), 0);
        int emptyOrders  = Objects.requireNonNullElse(statusCount.get(CellStatus.EMPTY), 0);
        int errorOrders  = Objects.requireNonNullElse(statusCount.get(CellStatus.ERROR), 0);

        sb.append(ANSI_BOLD).append("  📋 網格統計").append(ANSI_RESET).append('\n');
        sb.append(String.format("  %-20s %s%-5d%s  %-20s %s%-5d%s%n",
            "活躍掛單數:", ANSI_GREEN, activeOrders, ANSI_RESET,
            "空閒格數:", ANSI_WHITE, emptyOrders, ANSI_RESET));
        sb.append(String.format("  %-20s %s%-5d%s  %-20s %s%-5d%s%n",
            "總成交次數:", ANSI_CYAN, orderExecutor.getTotalOrdersFilled(), ANSI_RESET,
            "異常格數:", errorOrders > 0 ? ANSI_RED : ANSI_WHITE, errorOrders, ANSI_RESET));
        sb.append(String.format("  %-20s %-5d  %-20s %-5d%n",
            "總下單次數:", orderExecutor.getTotalOrdersPlaced(),
            "下單失敗數:", orderExecutor.getTotalOrdersFailed()));

        sb.append('\n');
        // 網格配置摘要
        sb.append(ANSI_BOLD).append("  ⚙️  網格配置").append(ANSI_RESET).append('\n');
        sb.append(String.format("  交易對: %-10s 格數: %-5d 間距: %s USDT%n",
            gridConfig.getSymbol(), gridConfig.getGridCount(), gridConfig.getGridSpacing().toPlainString()));
        sb.append(String.format("  範圍:   [%s, %s] USDT%n",
            gridConfig.getLowerPrice().toPlainString(), gridConfig.getUpperPrice().toPlainString()));

        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染 API 狀態。
     */
    private void renderApiStatus(StringBuilder sb) {
        long pingMs = binanceApi.getLastPingLatencyMs();
        double remainingWeight = rateLimiter.getRemainingWeight();
        long throttledCount = rateLimiter.getThrottledCount();

        String pingColor = pingMs < 0 ? ANSI_RED : (pingMs < 100 ? ANSI_GREEN : ANSI_YELLOW);
        String pingStr = pingMs < 0 ? "N/A" : pingMs + "ms";

        // API Weight 顏色（< 200 紅色警告）
        String weightColor = remainingWeight < 200 ? ANSI_RED :
                             (remainingWeight < 500 ? ANSI_YELLOW : ANSI_GREEN);

        sb.append(ANSI_BOLD).append("  🌐 API 狀態").append(ANSI_RESET).append('\n');
        sb.append(String.format("  網路延遲:    %s%s%s%n", pingColor, pingStr, ANSI_RESET));
        sb.append(String.format("  剩餘 Weight: %s%.0f / 960%s（安全上限）%n",
            weightColor, remainingWeight, ANSI_RESET));
        sb.append(String.format("  限流次數:    %d 次%n", throttledCount));

        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染 CoinGlass BTC 市場分析面板。
     * 顯示：Fear &amp; Greed 指數、未平倉量、24h 爆倉、Coinbase 溢價、多空比、資金費率。
     */
    private void renderCoinGlassPanel(StringBuilder sb) {
        // 在後台觸發數據刷新（非阻塞：若快取未過期則立即返回）
        try {
            Thread.ofVirtual().name("cg-refresh").start(() -> coinGlassApi.refresh(false));
        } catch (Exception ignored) { /* 不影響主渲染 */ }

        sb.append(ANSI_BOLD).append(ANSI_CYAN)
          .append("  🔍 BTC 市場分析 (CoinGlass)")
          .append(ANSI_RESET).append('\n');

        // ─── 狀態列 ───
        String statusStr;
        if (!coinGlassApi.hasApiKey()) {
            statusStr = ANSI_YELLOW + "⚠ API Key 未設置（設 COINGLASS_API_KEY）" + ANSI_RESET;
        } else if (!coinGlassApi.isApiAvailable()) {
            String err = coinGlassApi.getLastErrorMsg();
            statusStr = ANSI_RED + "✗ 連線失敗" + (err != null ? ": " + err : "") + ANSI_RESET;
        } else {
            statusStr = ANSI_GREEN + "✓ 更新: " + coinGlassApi.getLastRefreshTimeStr() + ANSI_RESET;
        }
        sb.append("  狀態: ").append(statusStr).append('\n');
        sb.append('\n');

        // ─── Fear & Greed Index ───
        renderFearGreed(sb);

        // ─── Open Interest ───
        var oi = coinGlassApi.getOpenInterestUsd();
        var oiChange = coinGlassApi.getOpenInterestChange24h();
        String oiStr = oi.compareTo(BigDecimal.ZERO) > 0
            ? CoinGlassApiAdapter.formatBigNumber(oi) + " USD"
            : "N/A";
        String oiChangeColor = oiChange.compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED;
        String oiChangeSign = oiChange.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        sb.append(String.format("  %-26s %s%s  (%s%s%s%%)%n",
            "📊 未平倉量 (OI):",
            ANSI_CYAN, oiStr, ANSI_RESET,
            oiChangeColor, oiChangeSign + oiChange.toPlainString(), ANSI_RESET));

        // ─── 24h 爆倉 ───
        var longLiq  = coinGlassApi.getLongLiquidation24h();
        var shortLiq = coinGlassApi.getShortLiquidation24h();
        sb.append(String.format("  %-26s %s%s%s  /  %s%s%s%n",
            "💥 24h 爆倉 (多/空):",
            ANSI_GREEN, CoinGlassApiAdapter.formatBigNumber(longLiq), ANSI_RESET,
            ANSI_RED, CoinGlassApiAdapter.formatBigNumber(shortLiq), ANSI_RESET));

        // ─── Coinbase Premium ───
        var cbPremium = coinGlassApi.getCoinbasePremium();
        String cbColor = cbPremium.compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED;
        String cbSign  = cbPremium.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        String cbTip   = cbPremium.compareTo(BigDecimal.ZERO) > 0 ? " (機構買入信號)" :
                         cbPremium.compareTo(BigDecimal.ZERO) < 0 ? " (拋壓信號)" : "";
        sb.append(String.format("  %-26s %s%s%s USDT%s%n",
            "📈 Coinbase 溢價:",
            cbColor, cbSign + cbPremium.toPlainString(), ANSI_RESET, cbTip));

        // ─── Long/Short Ratio ───
        var lsRatio = coinGlassApi.getLongShortRatio();
        var longPct  = coinGlassApi.getLongRatio();
        var shortPct = coinGlassApi.getShortRatio();
        String lsStr = lsRatio.compareTo(BigDecimal.ZERO) > 0
            ? String.format("%s %.4f%s  (多 %s%.2f%%%s / 空 %s%.2f%%%s)",
                ANSI_CYAN, lsRatio.doubleValue(), ANSI_RESET,
                ANSI_GREEN, longPct.doubleValue(), ANSI_RESET,
                ANSI_RED,   shortPct.doubleValue(), ANSI_RESET)
            : ANSI_WHITE + "N/A" + ANSI_RESET;
        sb.append(String.format("  %-26s%s%n", "⚖️  多空比 (L/S Ratio):", lsStr));

        // ─── Funding Rate ───
        var fr = coinGlassApi.getFundingRate();
        String frColor = fr.compareTo(BigDecimal.ZERO) >= 0 ? ANSI_GREEN : ANSI_RED;
        String frSign  = fr.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
        String frTip   = fr.compareTo(BigDecimal.valueOf(0.05)) > 0 ? " ⚠ 多頭過熱" :
                         fr.compareTo(BigDecimal.valueOf(-0.05)) < 0 ? " ⚠ 空頭過熱" : "";
        sb.append(String.format("  %-26s %s%s%s%%%s%n",
            "💰 資金費率 (Binance):",
            frColor, frSign + fr.toPlainString(), ANSI_RESET, frTip));

        sb.append(horizontalLine()).append('\n');
    }

    /**
     * 渲染 Fear &amp; Greed 指數（帶彩色進度條）。
     */
    private void renderFearGreed(StringBuilder sb) {
        var fgi = coinGlassApi.getFearGreedIndex();
        String label = coinGlassApi.getFearGreedLabel();
        int value = fgi.intValue();

        // 根據分值選色
        String fgiColor;
        if (value < 0) {
            fgiColor = ANSI_WHITE;
        } else if (value <= 20) {
            fgiColor = ANSI_RED;
        } else if (value <= 40) {
            fgiColor = ANSI_YELLOW;
        } else if (value <= 60) {
            fgiColor = ANSI_WHITE;
        } else if (value <= 80) {
            fgiColor = ANSI_GREEN;
        } else {
            fgiColor = ANSI_MAGENTA;
        }

        // 進度條（20 格）
        String bar;
        if (value >= 0) {
            int filled = Math.min(20, value / 5);
            bar = "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "]";
        } else {
            bar = "[" + "?".repeat(20) + "]";
        }

        String fgiStr = value >= 0 ? String.valueOf(value) : "N/A";
        sb.append(String.format("  %-26s %s%s %-14s%s %s%n",
            "😱 恐懼貪婪指數:",
            fgiColor, fgiStr,
            "(" + label + ")",
            ANSI_RESET,
            fgiColor + bar + ANSI_RESET));
    }

    /**
     * 渲染網格格子狀態（最多顯示 20 格，超過則只顯示摘要）。
     */
    private void renderGridCells(StringBuilder sb) {
        sb.append(ANSI_BOLD).append("  📈 網格格子狀態（最近 20 格）").append(ANSI_RESET).append('\n');
        sb.append(String.format("  %-4s %-12s %-8s %-10s %-12s%n",
            "格#", "觸發價格(USDT)", "方向", "狀態", "成交量(BTC)"));
        sb.append("  " + "-".repeat(DASHBOARD_WIDTH - 2)).append('\n');

        int displayCount = Math.min(gridCells.size(), 20);

        // 顯示最靠近當前市場價格的格子
        BigDecimal currentPrice = Objects.requireNonNullElse(currentBtcPrice.get(), BigDecimal.ZERO);
        List<GridCell> cellsToDisplay = selectCellsNearPrice(currentPrice, displayCount);

        for (GridCell cell : cellsToDisplay) {
            CellStatus status = cell.getStatus();
            String statusColor = switch (status) {
                case ACTIVE -> ANSI_GREEN;
                case PARTIAL -> ANSI_CYAN;
                case PENDING -> ANSI_YELLOW;
                case FILLED -> ANSI_MAGENTA;
                case ERROR -> ANSI_RED;
                case CANCELLED -> ANSI_WHITE;
                default -> ANSI_WHITE;
            };

            String sideStr = cell.getOrderSide().name();
            String sideColor = "BUY".equals(sideStr) ? ANSI_GREEN : ANSI_RED;

            BigDecimal filledQty = cell.getFilledQuantity();

            // 如果當前格是最接近市場價的，高亮顯示
            boolean isNearMarket = currentPrice.compareTo(BigDecimal.ZERO) > 0 &&
                currentPrice.subtract(cell.getTriggerPrice()).abs()
                    .compareTo(gridConfig.getGridSpacing()) <= 0;
            String linePrefix = isNearMarket ? ANSI_BG_BLUE + ANSI_WHITE : "";
            String lineSuffix = isNearMarket ? ANSI_RESET : "";

            sb.append(String.format("  %s[%-3d] %-12s %s%-8s%s %s%-10s%s %-12s%s%n",
                linePrefix,
                cell.getCellIndex(),
                cell.getTriggerPrice().toPlainString(),
                sideColor, sideStr, ANSI_RESET,
                statusColor, status.name(), ANSI_RESET,
                filledQty.compareTo(BigDecimal.ZERO) > 0 ? filledQty.toPlainString() : "-",
                lineSuffix
            ));
        }

        if (gridCells.size() > displayCount) {
            sb.append(String.format("  ... 共 %d 格（只顯示靠近市場價的 %d 格）%n",
                gridCells.size(), displayCount));
        }
        sb.append('\n');
    }

    /**
     * 渲染頁腳。
     */
    private void renderFooter(StringBuilder sb) {
        sb.append(horizontalLine()).append('\n');
        sb.append(ANSI_CYAN)
          .append("  [Ctrl+C 優雅停止] [日誌: ./logs/fiorino.json.log]")
          .append(ANSI_RESET).append('\n');
    }

    // ============================================================
    // 計算方法
    // ============================================================

    /**
     * 計算所有 ACTIVE 格的未實現盈虧（估算值）。
     */
    private BigDecimal calculateUnrealizedPnl(BigDecimal currentPrice) {
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalPnl = BigDecimal.ZERO;
        for (GridCell cell : gridCells) {
            if (!cell.hasActiveOrder()) continue;

            BigDecimal filledQty = cell.getFilledQuantity();
            if (filledQty.compareTo(BigDecimal.ZERO) <= 0) continue;

            // 未實現盈虧 ≈ (現價 - 成交均價) × 成交量
            // 這是一個近似值，精確值需要記錄每次的成交均價
            BigDecimal avgPrice = cell.getTriggerPrice(); // 使用掛單價格近似
            BigDecimal pnl = currentPrice.subtract(avgPrice).multiply(filledQty);
            totalPnl = totalPnl.add(pnl);
        }

        return totalPnl.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 選取最靠近當前市場價格的格子（用於 Dashboard 顯示）。
     */
    private List<GridCell> selectCellsNearPrice(BigDecimal currentPrice, int count) {
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            // 無市場價格：顯示前 N 格
            return gridCells.subList(0, Math.min(count, gridCells.size()));
        }

        // 找到最靠近市場價的格子索引
        int closestIndex = 0;
        BigDecimal minDistance = null;
        for (int i = 0; i < gridCells.size(); i++) {
            BigDecimal distance = currentPrice.subtract(gridCells.get(i).getTriggerPrice()).abs();
            if (minDistance == null || distance.compareTo(minDistance) < 0) {
                minDistance = distance;
                closestIndex = i;
            }
        }

        // 以最近格為中心，取前後各 count/2 個格
        int half = count / 2;
        int start = Math.max(0, closestIndex - half);
        int end = Math.min(gridCells.size(), start + count);
        start = Math.max(0, end - count); // 調整 start 確保取到 count 個

        return gridCells.subList(start, end);
    }

    // ============================================================
    // 格式化工具
    // ============================================================

    /**
     * 格式化 Bot 運行時長。
     */
    private String formatUptime() {
        long secondsTotal = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        long hours = secondsTotal / 3600;
        long minutes = (secondsTotal % 3600) / 60;
        long seconds = secondsTotal % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * 生成水平分隔線。
     */
    private String horizontalLine() {
        return ANSI_BLUE + "  " + "═".repeat(DASHBOARD_WIDTH - 2) + ANSI_RESET;
    }

    /**
     * 居中對齊字串（填充空格）。
     */
    private String centerPad(String text, int width) {
        int textLen = text.replaceAll("\033\\[[0-9;]*m", "").length(); // 去除 ANSI 碼計算實際長度
        int totalPad = Math.max(0, width - textLen);
        int leftPad = totalPad / 2;
        int rightPad = totalPad - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    // ============================================================
    // AutoCloseable
    // ============================================================

    @Override
    public void close() {
        stop();
    }
}
