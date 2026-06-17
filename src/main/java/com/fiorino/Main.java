package com.fiorino;

import com.fiorino.application.executor.GridOrderExecutor;
import com.fiorino.application.orchestrator.GridOrchestrator;
import com.fiorino.application.ratelimit.RateLimiter;
import com.fiorino.cli.FiorinoLauncher;
import com.fiorino.cli.quant.BtcPredictionDashboard;
import com.fiorino.domain.model.GridConfig;
import com.fiorino.domain.statemachine.GridStateMachine;
import com.fiorino.infrastructure.api.BinanceApiAdapter;
import com.fiorino.infrastructure.api.CoinGlassApiAdapter;
import com.fiorino.infrastructure.dashboard.ConsoleDashboard;
import com.fiorino.infrastructure.persistence.LocalStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ============================================================
 * Main — 系統啟動入口（Entrypoint）
 * ============================================================
 *
 * 架構設計思維：
 * Main 類採用「手動依賴注入（Manual DI）」模式，不使用任何 IoC 容器。
 * 這確保：
 *   1. 啟動時間 < 1 秒（無 Spring 容器掃描開銷）
 *   2. 依賴圖完全可見（代碼即文檔）
 *   3. 故障點清晰（DI 失敗立即在 Main 可見）
 *
 * 配置加載策略（優先級從高到低）：
 *   1. 環境變數（BINANCE_API_KEY, BINANCE_SECRET_KEY 等）
 *   2. JVM 系統屬性（-Dfiorino.lower.price=50000）
 *   3. 硬編碼預設值（僅用於 testnet 測試）
 *
 * 優雅停止（Graceful Shutdown）：
 *   JVM Shutdown Hook 確保 SIGTERM 或 Ctrl+C 時，
 *   先撤銷所有掛單、持久化狀態，再退出 JVM。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /** 用於阻塞主線程直到收到關閉信號 */
    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * 程序主入口。
     *
     * @param args 命令行參數（不使用，配置通過環境變數傳入）
     */
    public static void main(String[] args) {
        log.info("Project Fiorino v2.0 啟動");

        // ═══════════════════════════════════════════════════════
        // 0. Headless 量化研究模式（供 launchd/cron 排程，無互動 UI）
        //    見 QUANT_BACKTEST_PLAN.md
        // ═══════════════════════════════════════════════════════
        for (int i = 0; i < args.length; i++) {
            if ("--collect".equals(args[i])) {
                com.fiorino.cli.quant.research.SnapshotCollector.runHeadless();
                System.exit(0);
            }
            if ("--backfill".equals(args[i])) {
                int days = 365;  // 預設回填一年
                if (i + 1 < args.length) {
                    try { days = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) {}
                }
                com.fiorino.cli.quant.research.HistoricalBackfiller.runHeadless(days);
                System.exit(0);
            }
            if ("--features".equals(args[i])) {
                com.fiorino.cli.quant.research.FeatureMatrixBuilder.runHeadless();
                System.exit(0);
            }
            if ("--backtest".equals(args[i])) {
                com.fiorino.cli.quant.research.BacktestEngine.runHeadless();
                System.exit(0);
            }
            if ("--live".equals(args[i])) {
                int minutes = 15;  // 預設 15 分鐘一輪（免費 API 限額下 1 分鐘亦安全）
                if (i + 1 < args.length) {
                    try { minutes = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) {}
                }
                com.fiorino.cli.quant.LivePredictionRunner.run(minutes);
                System.exit(0);
            }
        }

        // ═══════════════════════════════════════════════════════
        // 1. 加載配置（不強制 validate，允許 Binance Key 缺失時跑量化模式）
        // ═══════════════════════════════════════════════════════
        FiorinoConfig config = FiorinoConfig.loadFromEnvironment();
        log.info("配置加載完成：{}", config);

        // ═══════════════════════════════════════════════════════
        // 2. 顯示 CLI 主選單，等待使用者選擇
        // ═══════════════════════════════════════════════════════
        while (true) {
            FiorinoLauncher.LaunchMode mode = FiorinoLauncher.showMainMenu(config);

            switch (mode) {
                // 三種模式返回後皆迴圈重新顯示主選單（網格交易會阻塞至 Ctrl+C，故等同不返回）
                case GRID_TRADING       -> { launchGridTrading(config); return; }
                case BTC_QUANT_ANALYSIS -> launchQuantAnalysis(config);
                case QUANT_RESEARCH     -> com.fiorino.cli.quant.research.QuantResearchConsole.run();
                case EXIT               -> {
                    log.info("使用者選擇退出");
                    System.exit(0);
                }
            }
        }
    }

    // ============================================================
    // 模式 1：網格交易機器人
    // ============================================================

    private static void launchGridTrading(FiorinoConfig config) {
        log.info("啟動模式：網格交易機器人");

        // 網格交易需要 Binance API Key
        try {
            config.validateForGridTrading();
        } catch (IllegalArgumentException e) {
            System.out.println("\033[31m  ✗ " + e.getMessage() + "\033[0m");
            System.out.println("\033[33m  請設置 FIORINO_API_KEY 和 FIORINO_SECRET_KEY 後重新啟動\033[0m");
            System.out.println();
            return;
        }

        // L0：基礎設施組件
        RateLimiter rateLimiter = new RateLimiter();
        LocalStateManager stateManager = new LocalStateManager();

        // L1：Domain 組件
        GridConfig gridConfig = new GridConfig(
            config.getSymbol(),
            config.getLowerPrice(),
            config.getUpperPrice(),
            config.getGridCount(),
            config.getTotalInvestmentUsdt(),
            config.getMakerFeeRate()
        );
        GridStateMachine stateMachine = new GridStateMachine();

        // L2：Binance API 適配器
        BinanceApiAdapter binanceApi = new BinanceApiAdapter(
            config.getApiKey(),
            config.getSecretKey(),
            rateLimiter,
            config.isUseTestnet()
        );

        // L3：Application 層
        GridOrderExecutor orderExecutor = new GridOrderExecutor(
            gridConfig, stateMachine, binanceApi, stateManager, rateLimiter
        );
        GridOrchestrator orchestrator = new GridOrchestrator(
            gridConfig, stateMachine, binanceApi, stateManager, orderExecutor, rateLimiter
        );

        // L4：Dashboard
        AtomicReference<BigDecimal> priceRef = new AtomicReference<>(BigDecimal.ZERO);
        CoinGlassApiAdapter coinGlassApi = new CoinGlassApiAdapter(config.getCoinGlassApiKey());
        Thread.ofVirtual().name("cg-init-refresh").start(() -> coinGlassApi.refresh(true));

        ConsoleDashboard dashboard = new ConsoleDashboard(
            gridConfig, stateMachine, orchestrator.getGridCells(),
            binanceApi, stateManager, orderExecutor, rateLimiter, priceRef,
            coinGlassApi
        );
        orchestrator.setDashboard(dashboard);
        orchestrator.setExternalPriceRef(priceRef);

        // 注冊優雅停止
        registerShutdownHook(orchestrator, dashboard, stateManager, orderExecutor);

        // 啟動
        dashboard.start();
        try {
            orchestrator.start();
        } catch (Exception e) {
            log.error("Bot 啟動失敗", e);
            dashboard.stop();
            stateManager.close();
            System.exit(1);
            return;
        }

        log.info("網格交易 Bot 運行中，按 Ctrl+C 優雅停止");
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Project Fiorino 已正常退出");
    }

    // ============================================================
    // 模式 2：BTC 量化分析 & 價格預測
    // ============================================================

    private static void launchQuantAnalysis(FiorinoConfig config) {
        log.info("啟動模式：BTC 量化分析");
        // BtcPredictionDashboard 內部循環顯示直到使用者選 0；返回後由 main() 的迴圈重顯主選單。
        // 不在此重複實作選單路由（先前漏 QUANT_RESEARCH 分支的根因，已統一交給 main()）。
        BtcPredictionDashboard.run(config.getCoinGlassApiKey());
    }

    // ============================================================
    // JVM Shutdown Hook：優雅停止
    // ============================================================

    /**
     * 注冊 JVM Shutdown Hook，處理 SIGTERM / Ctrl+C 信號。
     * 按順序：
     *   1. 停止 Orchestrator（撤銷所有訂單）
     *   2. 停止 Dashboard
     *   3. 關閉 StateManager（HikariCP 連線池）
     *   4. 關閉 OrderExecutor（Virtual Thread 池）
     *   5. 釋放 CountDownLatch（主線程退出）
     */
    private static void registerShutdownHook(GridOrchestrator orchestrator,
                                              ConsoleDashboard dashboard,
                                              LocalStateManager stateManager,
                                              GridOrderExecutor orderExecutor) {
        Runtime.getRuntime().addShutdownHook(
            Thread.ofVirtual().name("fiorino-shutdown-hook").unstarted(() -> {
                log.info("收到關閉信號，開始優雅停止流程...");
                long start = System.currentTimeMillis();

                // 1. 停止 Orchestrator（撤銷所有訂單）
                try {
                    orchestrator.close();
                } catch (Exception e) {
                    log.error("Orchestrator 停止失敗", e);
                }

                // 2. 停止 Dashboard
                try {
                    dashboard.close();
                } catch (Exception e) {
                    log.error("Dashboard 停止失敗", e);
                }

                // 3. 關閉 StateManager
                try {
                    stateManager.close();
                } catch (Exception e) {
                    log.error("StateManager 關閉失敗", e);
                }

                // 4. 關閉 OrderExecutor
                try {
                    orderExecutor.close();
                } catch (Exception e) {
                    log.error("OrderExecutor 關閉失敗", e);
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("優雅停止完成 | 耗時: {}ms", elapsed);

                // 5. 釋放主線程
                shutdownLatch.countDown();
            })
        );

        log.info("JVM Shutdown Hook 已注冊");
    }

    // ============================================================
    // 內部配置類（從環境變數加載）
    // ============================================================

    /**
     * Bot 配置容器。
     * 所有敏感信息（API Key/Secret）從環境變數加載，不硬編碼。
     */
    public static final class FiorinoConfig {

        private final String symbol;
        private final String apiKey;
        private final String secretKey;
        private final BigDecimal lowerPrice;
        private final BigDecimal upperPrice;
        private final int gridCount;
        private final BigDecimal totalInvestmentUsdt;
        private final BigDecimal makerFeeRate;
        private final boolean useTestnet;
        /** CoinGlass API Key（可選，空字串表示限制模式） */
        private final String coinGlassApiKey;

        private FiorinoConfig(String symbol, String apiKey, String secretKey,
                               BigDecimal lowerPrice, BigDecimal upperPrice,
                               int gridCount, BigDecimal totalInvestmentUsdt,
                               BigDecimal makerFeeRate, boolean useTestnet,
                               String coinGlassApiKey) {
            this.symbol = symbol;
            this.apiKey = apiKey;
            this.secretKey = secretKey;
            this.lowerPrice = lowerPrice;
            this.upperPrice = upperPrice;
            this.gridCount = gridCount;
            this.totalInvestmentUsdt = totalInvestmentUsdt;
            this.makerFeeRate = makerFeeRate;
            this.useTestnet = useTestnet;
            this.coinGlassApiKey = coinGlassApiKey;
        }

        /**
         * 從環境變數加載配置。
         * 環境變數命名約定：FIORINO_* 前綴。
         *
         * @return 配置實例
         */
        public static FiorinoConfig loadFromEnvironment() {
            Map<String, String> env = System.getenv();

            // API 認證（必填，無預設值）— 同時支援環境變數和 JVM -D 屬性
            String apiKey = getEnvOrProperty(env, "FIORINO_API_KEY", "FIORINO_API_KEY", null);
            if (apiKey == null) apiKey = getEnvOrProperty(env, "FIORINO_API_KEY", "binance.api.key", null);
            String secretKey = getEnvOrProperty(env, "FIORINO_SECRET_KEY", "FIORINO_SECRET_KEY", null);
            if (secretKey == null) secretKey = getEnvOrProperty(env, "FIORINO_SECRET_KEY", "binance.secret.key", null);

            // CoinGlass API Key（可選，若未設置則以限制模式運行）
            String coinGlassApiKey = getEnvOrProperty(env, "COINGLASS_API_KEY", "coinglass.api.key", "");

            // 交易對
            String symbol = getEnvOrProperty(env, "FIORINO_SYMBOL", "fiorino.symbol", "BTCUSDT");

            // 網格參數
            BigDecimal lowerPrice = parseBigDecimal(
                getEnvOrProperty(env, "FIORINO_LOWER_PRICE", "fiorino.lower.price", "60000"));
            BigDecimal upperPrice = parseBigDecimal(
                getEnvOrProperty(env, "FIORINO_UPPER_PRICE", "fiorino.upper.price", "70000"));
            int gridCount = Integer.parseInt(
                getEnvOrProperty(env, "FIORINO_GRID_COUNT", "fiorino.grid.count", "20"));
            BigDecimal totalInvestment = parseBigDecimal(
                getEnvOrProperty(env, "FIORINO_INVESTMENT", "fiorino.investment", "1000"));
            BigDecimal makerFeeRate = parseBigDecimal(
                getEnvOrProperty(env, "FIORINO_FEE_RATE", "fiorino.fee.rate", "0.001"));

            // 是否使用測試網（預設：true，安全第一）
            boolean useTestnet = Boolean.parseBoolean(
                getEnvOrProperty(env, "FIORINO_TESTNET", "fiorino.testnet", "true"));

            return new FiorinoConfig(symbol,
                Objects.requireNonNullElse(apiKey, ""),
                Objects.requireNonNullElse(secretKey, ""),
                lowerPrice, upperPrice, gridCount, totalInvestment, makerFeeRate, useTestnet,
                Objects.requireNonNullElse(coinGlassApiKey, ""));
        }

        /**
         * 軟性驗證（僅記錄警告，不拋出異常）。
         * 適用於量化分析模式：即使 Binance Key 未設置也可繼續。
         */
        public void validate() {
            if (Objects.requireNonNullElse(apiKey, "").isBlank()) {
                log.warn("ℹ️  FIORINO_API_KEY 未設置，網格交易功能不可用");
            }
            if (lowerPrice.compareTo(upperPrice) >= 0) {
                log.warn("網格下界({})不小於上界({})，網格交易配置可能需要調整", lowerPrice, upperPrice);
            }
            if (useTestnet) {
                log.info("環境: TESTNET");
            }
            if (Objects.requireNonNullElse(coinGlassApiKey, "").isBlank()) {
                log.warn("ℹ️  COINGLASS_API_KEY 未設置，CoinGlass 面板將以限制模式顯示");
            }
            log.info("配置加載 | CoinGlass: {}",
                coinGlassApiKey.isBlank() ? "未設置" : "已設置");
        }

        /**
         * 嚴格驗證網格交易所需配置（Binance API Key、價格區間等）。
         * @throws IllegalArgumentException 如果必要配置缺失
         */
        public void validateForGridTrading() {
            if (Objects.requireNonNullElse(apiKey, "").isBlank()) {
                throw new IllegalArgumentException(
                    "API Key 未配置！請設置環境變數 FIORINO_API_KEY 或 JVM 屬性 -DFIORINO_API_KEY");
            }
            if (Objects.requireNonNullElse(secretKey, "").isBlank()) {
                throw new IllegalArgumentException(
                    "Secret Key 未配置！請設置環境變數 FIORINO_SECRET_KEY");
            }
            if (lowerPrice.compareTo(upperPrice) >= 0) {
                throw new IllegalArgumentException(
                    String.format("下界(%s)必須小於上界(%s)", lowerPrice, upperPrice));
            }
            if (gridCount < 2 || gridCount > 500) {
                throw new IllegalArgumentException("格數必須在 [2, 500] 範圍內: " + gridCount);
            }
            if (useTestnet) {
                log.warn("⚠️  當前使用測試網（Testnet）！若需使用主網，請設置 FIORINO_TESTNET=false");
            }
            log.info("網格交易配置驗證通過 | 環境: {} | 格數: {} | 範圍: [{}, {}] | 投入: {} USDT",
                useTestnet ? "TESTNET" : "⚠️ MAINNET", gridCount, lowerPrice, upperPrice, totalInvestmentUsdt);
        }

        /**
         * 優先從環境變數獲取，其次從 JVM 系統屬性，最後使用預設值。
         */
        private static String getEnvOrProperty(Map<String, String> env, String envKey,
                                                 String propKey, String defaultValue) {
            String envVal = env.get(envKey);
            if (envVal != null && !envVal.isBlank()) {
                return envVal;
            }
            String propVal = System.getProperty(propKey);
            if (propVal != null && !propVal.isBlank()) {
                return propVal;
            }
            return defaultValue;
        }

        private static BigDecimal parseBigDecimal(String str) {
            try {
                return new BigDecimal(Objects.requireNonNullElse(str, "0").trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("無效的數值配置: " + str, e);
            }
        }

        // Getters
        public String getSymbol() { return symbol; }
        public String getApiKey() { return apiKey; }
        public String getSecretKey() { return secretKey; }
        public BigDecimal getLowerPrice() { return lowerPrice; }
        public BigDecimal getUpperPrice() { return upperPrice; }
        public int getGridCount() { return gridCount; }
        public BigDecimal getTotalInvestmentUsdt() { return totalInvestmentUsdt; }
        public BigDecimal getMakerFeeRate() { return makerFeeRate; }
        public boolean isUseTestnet() { return useTestnet; }
        public String getCoinGlassApiKey() { return coinGlassApiKey; }

        @Override
        public String toString() {
            return String.format(
                "FiorinoConfig{symbol=%s, testnet=%b, grids=%d, range=[%s,%s], investment=%s USDT, cgKey=%s}",
                symbol, useTestnet, gridCount, lowerPrice, upperPrice, totalInvestmentUsdt,
                coinGlassApiKey.isBlank() ? "NOT SET" : "SET");
        }
    }
}
