package com.fiorino.infrastructure.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ============================================================
 * CoinGlassApiAdapter — CoinGlass REST API V4 適配器（Infrastructure Layer）
 * ============================================================
 *
 * 職責：
 *   從 CoinGlass API (https://open-api-v4.coinglass.com) 抓取 BTC 市場分析數據：
 *   - Crypto Fear &amp; Greed Index（恐懼貪婪指數）
 *   - BTC Aggregated Open Interest（聚合未平倉量）
 *   - BTC Liquidation History（多空爆倉統計）
 *   - Coinbase Premium Index（Coinbase 溢價指數）
 *   - BTC Long/Short Ratio（多空比）
 *   - BTC Funding Rate（資金費率）
 *
 * 認證方式：
 *   API Key 透過 Header 傳遞：CG-API-KEY: &lt;your_api_key&gt;
 *
 * 設計原則：
 *   - 快取結果（每 5 分鐘刷新一次，避免超限）
 *   - 失敗靜默降級（不影響主 Bot 運行）
 *   - 所有解析均有 null 防護
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class CoinGlassApiAdapter {

    private static final Logger log = LoggerFactory.getLogger(CoinGlassApiAdapter.class);

    // ============================================================
    // CoinGlass API 基礎端點
    // ============================================================

    private static final String BASE_URL = "https://open-api-v4.coinglass.com";

    // 端點定義
    private static final String ENDPOINT_FEAR_GREED    = "/api/index/fear-greed-history";
    private static final String ENDPOINT_OI_AGGREGATED = "/api/futures/openInterest/ohlc-aggregated-history";
    private static final String ENDPOINT_LIQUIDATION   = "/api/futures/liquidation/aggregated-history";
    private static final String ENDPOINT_COINBASE_PREMIUM = "/api/futures/market/coinbase-premium-index";
    private static final String ENDPOINT_LONG_SHORT    = "/api/futures/global-long-short-account-ratio/history";
    private static final String ENDPOINT_FUNDING_RATE  = "/api/futures/funding-rate/exchange-list";

    // ============================================================
    // HTTP 配置
    // ============================================================

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 分鐘快取

    // ============================================================
    // 核心組件
    // ============================================================

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // ============================================================
    // 快取的市場數據
    // ============================================================

    /** 當前恐懼貪婪指數 (0-100) */
    private volatile BigDecimal fearGreedIndex = BigDecimal.valueOf(-1);
    private volatile String fearGreedLabel = "N/A";

    /** 聚合未平倉量（USD） */
    private volatile BigDecimal openInterestUsd = BigDecimal.valueOf(-1);
    private volatile BigDecimal openInterestChange24h = BigDecimal.ZERO;

    /** 24h 多頭爆倉（USD） */
    private volatile BigDecimal longLiquidation24h = BigDecimal.ZERO;

    /** 24h 空頭爆倉（USD） */
    private volatile BigDecimal shortLiquidation24h = BigDecimal.ZERO;

    /** Coinbase 溢價（正值：Coinbase 價格 > Binance，機構買入信號） */
    private volatile BigDecimal coinbasePremium = BigDecimal.ZERO;

    /** 全球多空帳戶比 */
    private volatile BigDecimal longShortRatio = BigDecimal.valueOf(-1);
    private volatile BigDecimal longRatio = BigDecimal.ZERO;
    private volatile BigDecimal shortRatio = BigDecimal.ZERO;

    /** Binance BTC 資金費率（每 8 小時） */
    private volatile BigDecimal fundingRate = BigDecimal.ZERO;

    /** 上次成功刷新的時間 */
    private volatile Instant lastSuccessfulRefresh = null;

    /** 上次刷新嘗試時間（用於節流） */
    private volatile long lastRefreshAttemptMs = 0L;

    /** API 是否可用（有 key 且至少一次請求成功） */
    private volatile boolean apiAvailable = false;

    /** 最後一次錯誤訊息 */
    private volatile String lastErrorMsg = null;

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * @param apiKey CoinGlass API Key（若為空則以 Demo 模式運行，部分端點有限）
     */
    public CoinGlassApiAdapter(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_2)
            .build();
        this.objectMapper = new ObjectMapper();

        if (this.apiKey.isBlank()) {
            log.warn("CoinGlass API Key 未設置（COINGLASS_API_KEY）。CoinGlass 面板將以限制模式運行。");
        } else {
            log.info("CoinGlass API Adapter 初始化完成（API Key 已配置）");
        }
    }

    // ============================================================
    // 公開刷新方法（由外部定時器或 Dashboard 調用）
    // ============================================================

    /**
     * 刷新所有 BTC 市場分析數據。
     * 使用快取節流：若距上次刷新 < CACHE_TTL_MS，則跳過。
     *
     * @param force 若為 true，強制忽略快取直接刷新
     */
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastRefreshAttemptMs) < CACHE_TTL_MS) {
            return; // 快取未過期
        }
        lastRefreshAttemptMs = now;

        log.debug("開始刷新 CoinGlass BTC 市場數據...");

        // 各端點獨立嘗試，互不影響
        refreshFearGreed();
        refreshOpenInterest();
        refreshLiquidation();
        refreshCoinbasePremium();
        refreshLongShortRatio();
        refreshFundingRate();

        lastSuccessfulRefresh = Instant.now();
        apiAvailable = true;
        log.debug("CoinGlass 數據刷新完成");
    }

    // ============================================================
    // 各端點刷新方法
    // ============================================================

    private void refreshFearGreed() {
        try {
            String url = BASE_URL + ENDPOINT_FEAR_GREED + "?limit=1";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode entry = data.get(0);
                JsonNode dataList = entry.path("data_list");
                if (dataList.isArray() && !dataList.isEmpty()) {
                    double val = dataList.get(dataList.size() - 1).asDouble(-1);
                    fearGreedIndex = BigDecimal.valueOf(val).setScale(0, RoundingMode.HALF_UP);
                    fearGreedLabel = classifyFearGreed(fearGreedIndex.intValue());
                }
            }
        } catch (Exception e) {
            log.debug("Fear & Greed 刷新失敗: {}", e.getMessage());
        }
    }

    private void refreshOpenInterest() {
        try {
            String url = BASE_URL + ENDPOINT_OI_AGGREGATED
                + "?symbol=BTC&interval=h1&limit=2";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            if (data.isArray() && data.size() >= 1) {
                JsonNode latest = data.get(data.size() - 1);
                double closeOi = latest.path("c").asDouble(-1);
                if (closeOi >= 0) {
                    openInterestUsd = BigDecimal.valueOf(closeOi).setScale(0, RoundingMode.HALF_UP);
                }
                // 計算 24h 變化（若有前一筆）
                if (data.size() >= 2) {
                    JsonNode prev = data.get(0);
                    double prevClose = prev.path("c").asDouble(-1);
                    if (prevClose > 0 && closeOi > 0) {
                        double change = (closeOi - prevClose) / prevClose * 100;
                        openInterestChange24h = BigDecimal.valueOf(change).setScale(2, RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Open Interest 刷新失敗: {}", e.getMessage());
        }
    }

    private void refreshLiquidation() {
        try {
            // 取最近 24 小時的爆倉數據（interval=1h，limit=24）
            String url = BASE_URL + ENDPOINT_LIQUIDATION
                + "?symbol=BTC&exchange_list=Binance,OKX,Bybit&interval=1h&limit=24";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            if (!data.isArray()) return;

            double totalLong = 0, totalShort = 0;
            for (JsonNode entry : data) {
                totalLong  += entry.path("aggregated_long_liquidation_usd").asDouble(0);
                totalShort += entry.path("aggregated_short_liquidation_usd").asDouble(0);
            }
            longLiquidation24h  = BigDecimal.valueOf(totalLong).setScale(0, RoundingMode.HALF_UP);
            shortLiquidation24h = BigDecimal.valueOf(totalShort).setScale(0, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("Liquidation 刷新失敗: {}", e.getMessage());
        }
    }

    private void refreshCoinbasePremium() {
        try {
            String url = BASE_URL + ENDPOINT_COINBASE_PREMIUM + "?limit=1";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                // 取最後一筆
                JsonNode premiumList = data.path(0).path("price_list");
                JsonNode priceList   = data.path(0).path("data_list");
                if (premiumList.isArray() && !premiumList.isEmpty()) {
                    double prem = premiumList.get(premiumList.size() - 1).asDouble(0);
                    coinbasePremium = BigDecimal.valueOf(prem).setScale(4, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("Coinbase Premium 刷新失敗: {}", e.getMessage());
        }
    }

    private void refreshLongShortRatio() {
        try {
            // 全球多空比，Binance，BTC，1h，最近 1 筆
            String url = BASE_URL + ENDPOINT_LONG_SHORT
                + "?exchange=Binance&symbol=BTC&interval=h1&limit=1";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode entry = data.get(data.size() - 1);
                double lsRatio = entry.path("long_short_ratio").asDouble(-1);
                double longPct = entry.path("long_account").asDouble(0);
                double shortPct = entry.path("short_account").asDouble(0);
                if (lsRatio >= 0) {
                    longShortRatio = BigDecimal.valueOf(lsRatio).setScale(4, RoundingMode.HALF_UP);
                    longRatio = BigDecimal.valueOf(longPct * 100).setScale(2, RoundingMode.HALF_UP);
                    shortRatio = BigDecimal.valueOf(shortPct * 100).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("Long/Short Ratio 刷新失敗: {}", e.getMessage());
        }
    }

    private void refreshFundingRate() {
        try {
            String url = BASE_URL + ENDPOINT_FUNDING_RATE + "?symbol=BTC";
            JsonNode root = get(url);
            if (root == null) return;

            JsonNode data = root.path("data");
            // 找 Binance 的資金費率
            if (data.isArray()) {
                for (JsonNode entry : data) {
                    String exName = entry.path("exchange_name").asText("");
                    if ("Binance".equalsIgnoreCase(exName) || "binance".equalsIgnoreCase(exName)) {
                        double fr = entry.path("funding_rate").asDouble(0);
                        fundingRate = BigDecimal.valueOf(fr * 100).setScale(4, RoundingMode.HALF_UP);
                        break;
                    }
                }
                // 若沒有 Binance，取第一個
                if (fundingRate.compareTo(BigDecimal.ZERO) == 0 && data.size() > 0) {
                    double fr = data.get(0).path("funding_rate").asDouble(0);
                    fundingRate = BigDecimal.valueOf(fr * 100).setScale(4, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("Funding Rate 刷新失敗: {}", e.getMessage());
        }
    }

    // ============================================================
    // HTTP 請求核心
    // ============================================================

    /**
     * 執行 GET 請求並解析 JSON。
     * 返回 null 表示請求失敗。
     */
    private JsonNode get(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();

            if (!apiKey.isBlank()) {
                builder.header("CG-API-KEY", apiKey);
            }
            builder.header("Accept", "application/json");
            builder.header("User-Agent", "ProjectFiorino/1.0");

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 401 || status == 403) {
                lastErrorMsg = "API Key 無效（HTTP " + status + "）";
                log.warn("CoinGlass API 認證失敗: {}", lastErrorMsg);
                return null;
            }
            if (status == 429) {
                lastErrorMsg = "API 請求超限（HTTP 429）";
                log.warn("CoinGlass API 限速: {}", lastErrorMsg);
                return null;
            }
            if (status != 200) {
                lastErrorMsg = "HTTP " + status;
                log.debug("CoinGlass API 非 200 回應: {} URL: {}", status, url);
                return null;
            }

            lastErrorMsg = null;
            return objectMapper.readTree(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastErrorMsg = "請求被中斷";
            return null;
        } catch (Exception e) {
            lastErrorMsg = e.getMessage();
            log.debug("CoinGlass HTTP 請求失敗 URL={}: {}", url, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 根據分值分類恐懼貪婪指數標籤。
     */
    private String classifyFearGreed(int value) {
        if (value < 0)   return "N/A";
        if (value <= 20) return "Extreme Fear";
        if (value <= 40) return "Fear";
        if (value <= 60) return "Neutral";
        if (value <= 80) return "Greed";
        return "Extreme Greed";
    }

    /**
     * 格式化大數字為易讀格式（e.g., 1.23B, 456.7M）。
     */
    public static String formatBigNumber(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) return "N/A";
        double v = value.doubleValue();
        if (v >= 1_000_000_000) {
            return String.format("%.2fB", v / 1_000_000_000);
        } else if (v >= 1_000_000) {
            return String.format("%.2fM", v / 1_000_000);
        } else if (v >= 1_000) {
            return String.format("%.1fK", v / 1_000);
        }
        return String.format("%.2f", v);
    }

    // ============================================================
    // Getters — 供 ConsoleDashboard 讀取（線程安全：volatile）
    // ============================================================

    public BigDecimal getFearGreedIndex()       { return fearGreedIndex; }
    public String    getFearGreedLabel()         { return fearGreedLabel; }
    public BigDecimal getOpenInterestUsd()       { return openInterestUsd; }
    public BigDecimal getOpenInterestChange24h() { return openInterestChange24h; }
    public BigDecimal getLongLiquidation24h()    { return longLiquidation24h; }
    public BigDecimal getShortLiquidation24h()   { return shortLiquidation24h; }
    public BigDecimal getCoinbasePremium()       { return coinbasePremium; }
    public BigDecimal getLongShortRatio()        { return longShortRatio; }
    public BigDecimal getLongRatio()             { return longRatio; }
    public BigDecimal getShortRatio()            { return shortRatio; }
    public BigDecimal getFundingRate()           { return fundingRate; }
    public boolean   isApiAvailable()            { return apiAvailable; }
    public String    getLastErrorMsg()           { return lastErrorMsg; }
    public boolean   hasApiKey()                 { return !apiKey.isBlank(); }

    /**
     * 返回最後成功刷新的時間，若從未成功則返回 "Never"。
     */
    public String getLastRefreshTimeStr() {
        if (lastSuccessfulRefresh == null) return "Never";
        long secondsAgo = Instant.now().getEpochSecond() - lastSuccessfulRefresh.getEpochSecond();
        if (secondsAgo < 60) return secondsAgo + "s ago";
        if (secondsAgo < 3600) return (secondsAgo / 60) + "m ago";
        return (secondsAgo / 3600) + "h ago";
    }
}
