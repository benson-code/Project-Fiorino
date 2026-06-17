package com.fiorino.infrastructure.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fiorino.application.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ============================================================
 * BinanceApiAdapter — Binance REST API 適配器（Infrastructure Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類是「防腐層（Anti-Corruption Layer）」—— 隔離外部 Binance API 的
 * 不穩定性（網路抖動、API 版本升級、JSON 結構變更）與 Domain 層的純淨。
 *
 * 關鍵設計決策：
 *
 * 1. Java 11+ HttpClient（非 Apache HttpClient）
 *    - 原生非阻塞 API，與 Virtual Threads 搭配實現高效 I/O 並發
 *    - 無額外依賴，減少 JAR 體積
 *
 * 2. HMAC-SHA256 簽名
 *    - Binance 帶有資金操作的 API（下單、撤單）要求 SIGNED 類型認證
 *    - 每次請求計算 signature = HMAC_SHA256(queryString, secretKey)
 *    - timestamp 必須在 Binance 服務器時間 ±1000ms 內，否則拒絕
 *
 * 3. 指數退避重試（Exponential Backoff Retry）
 *    - 網路逾時、5xx 錯誤：自動重試，等待時間指數增長
 *    - HTTP 429（限速）：等待 Retry-After 頭所指定時間
 *    - HTTP 418（IP 封禁）：立即停止，記錄錯誤並觸發 Bot 熔斷
 *
 * 4. 全面防禦性空值處理
 *    - 所有 JsonNode 操作在訪問前先檢查 isNull() 或 isMissingNode()
 *    - 使用 Optional 包裝可能為空的回傳值
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class BinanceApiAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceApiAdapter.class);

    // ============================================================
    // API 端點常數
    // ============================================================

    private static final String BASE_URL = "https://api.binance.com";
    private static final String TESTNET_BASE_URL = "https://testnet.binance.vision";
    private static final String ENDPOINT_PING = "/api/v3/ping";
    private static final String ENDPOINT_SERVER_TIME = "/api/v3/time";
    private static final String ENDPOINT_TICKER_PRICE = "/api/v3/ticker/price";
    private static final String ENDPOINT_ORDER = "/api/v3/order";
    private static final String ENDPOINT_OPEN_ORDERS = "/api/v3/openOrders";
    private static final String ENDPOINT_ACCOUNT = "/api/v3/account";

    // ============================================================
    // 重試配置
    // ============================================================

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 500L;    // 初始退避 500ms
    private static final long MAX_BACKOFF_MS = 30_000L;  // 最大退避 30s
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // ============================================================
    // 核心依賴
    // ============================================================

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;

    /** 本地與 Binance 服務器的時間偏移量（毫秒），用於修正 timestamp */
    private volatile long serverTimeOffsetMs = 0L;

    /** 最後一次 Ping 延遲（毫秒） */
    private volatile long lastPingLatencyMs = -1L;

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 創建生產環境的 BinanceApiAdapter。
     *
     * @param apiKey      Binance API Key
     * @param secretKey   Binance Secret Key
     * @param rateLimiter 限流器實例
     * @param useTestnet  true 使用測試網（安全開發用）；false 使用主網
     */
    public BinanceApiAdapter(String apiKey, String secretKey,
                              RateLimiter rateLimiter, boolean useTestnet) {
        Objects.requireNonNull(apiKey, "API Key 不能為 null");
        Objects.requireNonNull(secretKey, "Secret Key 不能為 null");
        Objects.requireNonNull(rateLimiter, "RateLimiter 不能為 null");

        if (apiKey.isBlank()) throw new IllegalArgumentException("API Key 不能為空");
        if (secretKey.isBlank()) throw new IllegalArgumentException("Secret Key 不能為空");

        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.rateLimiter = rateLimiter;
        this.baseUrl = useTestnet ? TESTNET_BASE_URL : BASE_URL;

        // 構建 HttpClient：使用 Virtual Thread executor（Java 21）
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

        // 配置 ObjectMapper：支援 Java 8 時間類型
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        log.info("BinanceApiAdapter 初始化完成 | 環境: {} | BaseURL: {}",
            useTestnet ? "TESTNET" : "MAINNET", this.baseUrl);
    }

    // ============================================================
    // 公開 API（無需簽名）
    // ============================================================

    /**
     * Ping Binance API 服務器，測量網路延遲。
     *
     * @return 延遲毫秒數；-1 表示請求失敗
     */
    public long ping() {
        long start = System.currentTimeMillis();
        try {
            rateLimiter.acquire(RateLimiter.Weight.PING);
            HttpRequest request = buildGetRequest(ENDPOINT_PING, null);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 200) {
                long latency = System.currentTimeMillis() - start;
                this.lastPingLatencyMs = latency;
                log.debug("Ping 成功 | 延遲: {}ms", latency);
                return latency;
            }
        } catch (Exception e) {
            log.warn("Ping 失敗: {}", e.getMessage());
        }
        return -1L;
    }

    /**
     * 同步 Binance 服務器時間，計算本地時鐘偏移量。
     * 必須在 Bot 啟動時調用一次，之後定期（每 5 分鐘）調用以修正時鐘偏移。
     *
     * @throws IOException 如果 API 請求失敗
     */
    public void syncServerTime() throws IOException, InterruptedException {
        rateLimiter.acquire(1);
        HttpRequest request = buildGetRequest(ENDPOINT_SERVER_TIME, null);
        HttpResponse<String> response = executeWithRetry(request);

        if (response.statusCode() != 200) {
            throw new IOException("同步服務器時間失敗，HTTP " + response.statusCode());
        }

        JsonNode json = parseJson(response.body());
        long serverTime = safeGetLong(json, "serverTime");
        if (serverTime <= 0) {
            throw new IOException("服務器時間回應格式異常：" + response.body());
        }

        long localTime = System.currentTimeMillis();
        this.serverTimeOffsetMs = serverTime - localTime;
        log.info("服務器時間同步完成 | 偏移量: {}ms | 服務器時間: {}", serverTimeOffsetMs, serverTime);
    }

    /**
     * 獲取當前 BTC/USDT 市場價格。
     *
     * @param symbol 交易對（例如 "BTCUSDT"）
     * @return 當前價格；若失敗返回 Optional.empty()
     */
    public Optional<BigDecimal> getCurrentPrice(String symbol) {
        Objects.requireNonNull(symbol, "交易對符號不能為 null");

        try {
            rateLimiter.acquire(RateLimiter.Weight.GET_TICKER_PRICE);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            HttpRequest request = buildGetRequest(ENDPOINT_TICKER_PRICE, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 200) {
                JsonNode json = parseJson(response.body());
                String priceStr = safeGetString(json, "price");
                if (priceStr == null || priceStr.isBlank()) {
                    log.warn("獲取 {} 價格回應中 price 欄位為空", symbol);
                    return Optional.empty();
                }
                try {
                    return Optional.of(new BigDecimal(priceStr));
                } catch (NumberFormatException e) {
                    log.error("價格格式解析失敗: {}", priceStr, e);
                    return Optional.empty();
                }
            }

            log.warn("獲取 {} 價格失敗 | HTTP {}: {}", symbol, response.statusCode(), response.body());
            return Optional.empty();

        } catch (Exception e) {
            log.error("獲取 {} 市場價格時發生異常: {}", symbol, e.getMessage(), e);
            return Optional.empty();
        }
    }

    // ============================================================
    // 帶簽名的交易 API
    // ============================================================

    /**
     * 下限價單（Limit Order）。
     *
     * @param symbol   交易對
     * @param side     "BUY" 或 "SELL"
     * @param quantity 下單數量（BTC，6位小數）
     * @param price    限價（USDT，2位小數）
     * @return 訂單 ID（String 格式）；失敗時返回 Optional.empty()
     */
    public Optional<String> placeLimitOrder(String symbol, String side,
                                             BigDecimal quantity, BigDecimal price) {
        Objects.requireNonNull(symbol, "交易對不能為 null");
        Objects.requireNonNull(side, "方向不能為 null");
        Objects.requireNonNull(quantity, "數量不能為 null");
        Objects.requireNonNull(price, "價格不能為 null");

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("下單數量必須大於零: {}", quantity);
            return Optional.empty();
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("下單價格必須大於零: {}", price);
            return Optional.empty();
        }

        try {
            rateLimiter.acquire(RateLimiter.Weight.PLACE_ORDER);

            // 構建帶簽名的請求參數
            Map<String, String> params = buildSignedParams(Map.of(
                "symbol", symbol,
                "side", side,
                "type", "LIMIT",
                "timeInForce", "GTC",  // Good-Till-Cancel：掛單直到成交或手動撤銷
                "quantity", quantity.toPlainString(),
                "price", price.toPlainString()
            ));

            HttpRequest request = buildSignedPostRequest(ENDPOINT_ORDER, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 200) {
                JsonNode json = parseJson(response.body());
                long orderId = safeGetLong(json, "orderId");
                if (orderId <= 0) {
                    log.error("下單成功但 orderId 異常: {}", response.body());
                    return Optional.empty();
                }
                String orderIdStr = String.valueOf(orderId);
                log.info("下單成功 | {} {} {} @ {} | orderId: {}", symbol, side, quantity, price, orderIdStr);
                return Optional.of(orderIdStr);
            }

            log.error("下單失敗 | HTTP {}: {} | 參數: {} {} {} @ {}",
                response.statusCode(), response.body(), symbol, side, quantity, price);
            return Optional.empty();

        } catch (Exception e) {
            log.error("下單時發生異常 | {} {} {} @ {}: {}", symbol, side, quantity, price, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 撤銷指定訂單。
     *
     * @param symbol  交易對
     * @param orderId 要撤銷的訂單 ID
     * @return true 成功撤銷；false 失敗
     */
    public boolean cancelOrder(String symbol, String orderId) {
        Objects.requireNonNull(symbol, "交易對不能為 null");
        Objects.requireNonNull(orderId, "訂單 ID 不能為 null");

        try {
            rateLimiter.acquire(RateLimiter.Weight.CANCEL_ORDER);

            Map<String, String> params = buildSignedParams(Map.of(
                "symbol", symbol,
                "orderId", orderId
            ));

            HttpRequest request = buildSignedDeleteRequest(ENDPOINT_ORDER, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 200) {
                log.info("撤單成功 | {} orderId: {}", symbol, orderId);
                return true;
            }
            if (response.statusCode() == 400) {
                // Binance error -2011: Unknown order sent
                // 訂單不存在（可能已成交），視為成功
                JsonNode json = parseJson(response.body());
                int errorCode = (int) safeGetLong(json, "code");
                if (errorCode == -2011) {
                    log.warn("撤單時訂單 {} 不存在（可能已成交），視為成功", orderId);
                    return true;
                }
            }

            log.error("撤單失敗 | {} orderId: {} | HTTP {}: {}",
                symbol, orderId, response.statusCode(), response.body());
            return false;

        } catch (Exception e) {
            log.error("撤單時發生異常 | orderId {}: {}", orderId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 查詢指定訂單的當前狀態。
     *
     * @param symbol  交易對
     * @param orderId 訂單 ID
     * @return 訂單狀態的 JSON 節點；失敗時返回 Optional.empty()
     */
    public Optional<JsonNode> getOrderStatus(String symbol, String orderId) {
        Objects.requireNonNull(symbol, "交易對不能為 null");
        Objects.requireNonNull(orderId, "訂單 ID 不能為 null");

        try {
            rateLimiter.acquire(RateLimiter.Weight.GET_ORDER);

            Map<String, String> params = buildSignedParams(Map.of(
                "symbol", symbol,
                "orderId", orderId
            ));

            HttpRequest request = buildGetRequest(ENDPOINT_ORDER, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 200) {
                return Optional.of(parseJson(response.body()));
            }

            log.warn("查詢訂單失敗 | {} orderId: {} | HTTP {}: {}",
                symbol, orderId, response.statusCode(), response.body());
            return Optional.empty();

        } catch (Exception e) {
            log.error("查詢訂單時發生異常 | orderId {}: {}", orderId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 獲取賬戶資產餘額（BTC + USDT）。
     *
     * @return 資產 Map（key: 資產名稱，value: 可用餘額）；失敗時返回空 Map
     */
    public Map<String, BigDecimal> getAccountBalances() {
        try {
            rateLimiter.acquire(RateLimiter.Weight.GET_ACCOUNT_INFO);

            Map<String, String> params = buildSignedParams(Map.of());
            HttpRequest request = buildGetRequest(ENDPOINT_ACCOUNT, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() != 200) {
                log.error("查詢賬戶餘額失敗 | HTTP {}: {}", response.statusCode(), response.body());
                return Collections.emptyMap();
            }

            JsonNode json = parseJson(response.body());
            JsonNode balances = json.path("balances");
            if (balances.isNull() || balances.isMissingNode() || !balances.isArray()) {
                log.error("賬戶餘額回應格式異常：balances 欄位缺失或非數組");
                return Collections.emptyMap();
            }

            Map<String, BigDecimal> result = new LinkedHashMap<>();
            for (JsonNode balance : balances) {
                String asset = safeGetString(balance, "asset");
                String freeStr = safeGetString(balance, "free");

                if (asset == null || asset.isBlank() || freeStr == null || freeStr.isBlank()) {
                    continue;
                }

                // 只保留 BTC 和 USDT
                if (Objects.equals("BTC", asset) || Objects.equals("USDT", asset)) {
                    try {
                        BigDecimal free = new BigDecimal(freeStr);
                        result.put(asset, free);
                    } catch (NumberFormatException e) {
                        log.warn("解析 {} 餘額失敗，數值: {}", asset, freeStr);
                    }
                }
            }

            return Collections.unmodifiableMap(result);

        } catch (Exception e) {
            log.error("獲取賬戶餘額時發生異常: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * 獲取所有活躍掛單（用於重啟對賬）。
     *
     * @param symbol 交易對
     * @return 訂單列表；失敗時返回空列表
     */
    public List<JsonNode> getAllOpenOrders(String symbol) {
        Objects.requireNonNull(symbol, "交易對不能為 null");

        try {
            rateLimiter.acquire(RateLimiter.Weight.GET_ALL_OPEN_ORDERS);

            Map<String, String> params = buildSignedParams(Map.of("symbol", symbol));
            HttpRequest request = buildGetRequest(ENDPOINT_OPEN_ORDERS, params);
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() != 200) {
                log.error("查詢活躍訂單失敗 | HTTP {}: {}", response.statusCode(), response.body());
                return Collections.emptyList();
            }

            JsonNode json = parseJson(response.body());
            if (!json.isArray()) {
                log.error("查詢活躍訂單回應格式異常（非數組）: {}", response.body());
                return Collections.emptyList();
            }

            List<JsonNode> orders = new ArrayList<>();
            for (JsonNode order : json) {
                orders.add(order);
            }
            log.info("查詢 {} 活躍訂單: {} 筆", symbol, orders.size());
            return Collections.unmodifiableList(orders);

        } catch (Exception e) {
            log.error("查詢活躍訂單時發生異常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ============================================================
    // 工具方法：HTTP 請求構建
    // ============================================================

    /**
     * 構建不帶簽名的 GET 請求。
     */
    private HttpRequest buildGetRequest(String endpoint, Map<String, String> params) {
        String url = baseUrl + endpoint;
        if (params != null && !params.isEmpty()) {
            url += "?" + buildQueryString(params);
        }
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-MBX-APIKEY", apiKey)
            .GET()
            .build();
    }

    /**
     * 構建帶簽名的 POST 請求（用於下單等需要認證的操作）。
     */
    private HttpRequest buildSignedPostRequest(String endpoint, Map<String, String> params) {
        String url = baseUrl + endpoint;
        String body = buildQueryString(params);
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-MBX-APIKEY", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    /**
     * 構建帶簽名的 DELETE 請求（用於撤單）。
     */
    private HttpRequest buildSignedDeleteRequest(String endpoint, Map<String, String> params) {
        String url = baseUrl + endpoint + "?" + buildQueryString(params);
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-MBX-APIKEY", apiKey)
            .DELETE()
            .build();
    }

    // ============================================================
    // 工具方法：指數退避重試
    // ============================================================

    /**
     * 執行 HTTP 請求，失敗時自動指數退避重試。
     *
     * @param request HTTP 請求
     * @return HTTP 回應
     * @throws IOException 如果達到最大重試次數後仍失敗
     */
    private HttpResponse<String> executeWithRetry(HttpRequest request) throws IOException, InterruptedException {
        int attempt = 0;
        long backoffMs = BASE_BACKOFF_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                // HTTP 200-299：成功
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response;
                }

                // HTTP 429：觸發限速
                if (response.statusCode() == 429) {
                    String retryAfter = response.headers().firstValue("Retry-After").orElse("60");
                    long waitSec;
                    try {
                        waitSec = Long.parseLong(retryAfter);
                    } catch (NumberFormatException e) {
                        waitSec = 60;
                    }
                    log.error("觸發 Binance 限速（HTTP 429）！等待 {}秒 後重試（第{}/{}次）",
                        waitSec, attempt, MAX_RETRY_ATTEMPTS);
                    Thread.sleep(waitSec * 1000L);
                    continue;
                }

                // HTTP 418：IP 被封禁，立即拋出，不重試
                if (response.statusCode() == 418) {
                    log.error("IP 遭 Binance 封禁（HTTP 418）！請等待封禁解除後再啟動。");
                    throw new IOException("IP 封禁（HTTP 418），禁止重試");
                }

                // HTTP 5xx：服務器錯誤，重試
                if (response.statusCode() >= 500) {
                    log.warn("Binance 服務器錯誤 HTTP {} | 第{}/{}次重試，等待{}ms",
                        response.statusCode(), attempt, MAX_RETRY_ATTEMPTS, backoffMs);
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    continue;
                }

                // 其他非 2xx（例如 400 Bad Request）：不重試，直接返回
                log.warn("API 請求返回非成功狀態 HTTP {}: {}", response.statusCode(), response.body());
                return response;

            } catch (IOException e) {
                log.warn("API 請求發生 I/O 異常（{}） | 第{}/{}次重試，等待{}ms | 原因: {} | URL: {}",
                    e.getClass().getSimpleName(), attempt, MAX_RETRY_ATTEMPTS, backoffMs, e.getMessage(), request.uri());
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw new IOException("API 請求達到最大重試次數（" + MAX_RETRY_ATTEMPTS + "次）後仍失敗", e);
                }
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }

        throw new IOException("達到最大重試次數 " + MAX_RETRY_ATTEMPTS + " 次，API 請求最終失敗");
    }

    // ============================================================
    // 工具方法：簽名與參數處理
    // ============================================================

    /**
     * 構建帶有 timestamp 和 signature 的簽名參數 Map。
     * 返回的 Map 是有序的（LinkedHashMap），確保 signature 在最後。
     *
     * @param extraParams 額外的業務參數（不含 timestamp 和 signature）
     * @return 包含簽名的完整參數 Map
     */
    private Map<String, String> buildSignedParams(Map<String, String> extraParams) {
        Map<String, String> params = new LinkedHashMap<>();

        // 添加業務參數
        if (extraParams != null) {
            params.putAll(extraParams);
        }

        // 使用修正後的時間戳（考慮服務器時鐘偏移）
        long timestamp = System.currentTimeMillis() + serverTimeOffsetMs;
        params.put("timestamp", String.valueOf(timestamp));
        params.put("recvWindow", "5000"); // 請求有效窗口：5秒

        // 計算 HMAC-SHA256 簽名
        String queryString = buildQueryString(params);
        String signature = hmacSha256(queryString, secretKey);
        params.put("signature", signature);

        return params;
    }

    /**
     * 將參數 Map 轉換為 URL 查詢字符串（key1=val1&key2=val2）。
     * 對值進行 URL 編碼，確保特殊字符安全傳輸。
     */
    private String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(
                Objects.requireNonNullElse(value, ""), StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /**
     * 計算 HMAC-SHA256 簽名。
     *
     * @param data      待簽名的數據（query string）
     * @param secretKey Binance Secret Key
     * @return 十六進制格式的簽名字符串
     */
    private String hmacSha256(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            // 轉換為十六進制字符串
            StringBuilder hexBuilder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hexBuilder.append(String.format("%02x", b));
            }
            return hexBuilder.toString();
        } catch (Exception e) {
            // HMAC-SHA256 是標準算法，此異常實際上不應發生
            throw new RuntimeException("HMAC-SHA256 簽名計算失敗", e);
        }
    }

    // ============================================================
    // 工具方法：防禦性 JSON 解析
    // ============================================================

    /**
     * 安全解析 JSON 字符串，異常時拋出 IOException。
     */
    private JsonNode parseJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("API 回應體為空，無法解析 JSON");
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IOException("JSON 解析失敗: " + body.substring(0, Math.min(200, body.length())), e);
        }
    }

    /**
     * 安全獲取 JsonNode 中的 long 欄位，不存在或格式錯誤時返回 -1。
     */
    private long safeGetLong(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return -1L;
        }
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isNull() || fieldNode.isMissingNode()) {
            return -1L;
        }
        try {
            return fieldNode.asLong(-1L);
        } catch (Exception e) {
            log.warn("解析 JSON 欄位 {} 為 long 失敗: {}", field, fieldNode);
            return -1L;
        }
    }

    /**
     * 安全獲取 JsonNode 中的 String 欄位，不存在時返回 null。
     */
    private String safeGetString(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        JsonNode fieldNode = node.path(field);
        if (fieldNode.isNull() || fieldNode.isMissingNode()) {
            return null;
        }
        return fieldNode.asText(null);
    }

    // ============================================================
    // 統計查詢
    // ============================================================

    /**
     * 獲取最後一次 Ping 延遲（毫秒）。
     * -1 表示尚未執行 Ping 或最後一次 Ping 失敗。
     */
    public long getLastPingLatencyMs() {
        return lastPingLatencyMs;
    }

    /**
     * 獲取當前剩餘 API Weight。
     */
    public double getRemainingApiWeight() {
        return rateLimiter.getRemainingWeight();
    }
}
