package com.fiorino.application.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ============================================================
 * RateLimiter — Token Bucket 限流器（Application Layer）
 * ============================================================
 *
 * 架構設計思維：
 * Binance API 使用「Request Weight」計費系統，而非單純的「請求次數」。
 * 每個 API 端點消耗不同的 Weight（例如：下單 = 1 weight，
 * 查詢所有訂單 = 40 weight，K 線數據 = 1-10 weight）。
 * 每個 IP 每分鐘最多 1200 weight（超出返回 HTTP 429，封禁時間從 1 分鐘到永久不等）。
 *
 * 本實作採用「滑動窗口 Token Bucket」算法：
 * - 令牌以固定速率（1200/60 = 20 tokens/second）生成
 * - 每次 API 調用前，嘗試消耗對應 weight 的令牌
 * - 若令牌不足，計算需等待時間後阻塞（使用 Virtual Thread，阻塞無成本）
 *
 * 無鎖設計（Lock-Free）：
 * 使用 AtomicLong 的 CAS（Compare-And-Swap）操作實現線程安全的令牌桶，
 * 無需 synchronized 或 ReentrantLock，在高並發場景下性能最優。
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    // ============================================================
    // Binance API Weight 常數
    // ============================================================

    /** Binance 每分鐘最大 Weight（保守值，官方上限為 1200） */
    private static final long MAX_WEIGHT_PER_MINUTE = 1200L;

    /** 安全緩衝：只使用 80% 的配額，預留 20% 給緊急查詢 */
    private static final long SAFE_WEIGHT_PER_MINUTE = (long) (MAX_WEIGHT_PER_MINUTE * 0.8);

    /** 令牌生成速率：每毫秒生成多少 weight 的令牌 */
    private static final double TOKENS_PER_MS = (double) SAFE_WEIGHT_PER_MINUTE / 60_000.0;

    // ============================================================
    // 核心欄位
    // ============================================================

    /** 當前可用令牌數（乘以 1000 以保存小數精度，即實際 weight * 1000） */
    private final AtomicLong availableTokensX1000;

    /** 最後一次令牌補充的時間戳（毫秒） */
    private final AtomicLong lastRefillTimeMs;

    /** 令牌桶最大容量（= SAFE_WEIGHT_PER_MINUTE * 1000） */
    private final long maxTokensX1000;

    /** 統計：累計被限流次數 */
    private final AtomicLong throttledCount = new AtomicLong(0);

    /** 統計：累計消耗的 Weight */
    private final AtomicLong totalWeightConsumed = new AtomicLong(0);

    /**
     * Binance 常用 API 端點的 Weight 常數。
     * 直接在此列舉，避免魔法數字。
     */
    public static final class Weight {
        public static final int PLACE_ORDER = 1;
        public static final int CANCEL_ORDER = 1;
        public static final int GET_ORDER = 2;
        public static final int GET_ALL_OPEN_ORDERS = 40;
        public static final int GET_ACCOUNT_INFO = 20;
        public static final int GET_TICKER_PRICE = 2;
        public static final int GET_EXCHANGE_INFO = 20;
        public static final int PING = 1;

        private Weight() {}
    }

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 使用預設的 Binance API 安全配額創建限流器。
     */
    public RateLimiter() {
        this.maxTokensX1000 = SAFE_WEIGHT_PER_MINUTE * 1000L;
        // 初始令牌設為最大容量的 50%，避免啟動瞬間突發請求
        this.availableTokensX1000 = new AtomicLong(maxTokensX1000 / 2);
        this.lastRefillTimeMs = new AtomicLong(System.currentTimeMillis());

        log.info("RateLimiter 初始化完成 | 安全限額: {}/min | 補充速率: {:.4f} tokens/ms",
            SAFE_WEIGHT_PER_MINUTE, TOKENS_PER_MS);
    }

    // ============================================================
    // 核心方法
    // ============================================================

    /**
     * 消耗指定 Weight 的令牌，如果令牌不足則阻塞等待。
     *
     * 此方法設計為在 Virtual Thread 環境中調用：
     * - 當令牌不足時，使用 Thread.sleep() 阻塞
     * - 在 Virtual Thread 中，sleep 是平台無成本操作（不佔用 Carrier Thread）
     * - Platform Thread 調用此方法時，等待期間會佔用一個 OS 線程（應避免）
     *
     * @param weight 本次請求消耗的 API Weight
     * @throws InterruptedException 如果等待過程中線程被中斷
     * @throws IllegalArgumentException 如果 weight <= 0
     */
    public void acquire(int weight) throws InterruptedException {
        if (weight <= 0) {
            throw new IllegalArgumentException("消耗的 Weight 必須大於零，實際值: " + weight);
        }
        if (weight > SAFE_WEIGHT_PER_MINUTE) {
            throw new IllegalArgumentException(
                String.format("請求的 Weight(%d) 超過每分鐘安全配額(%d)", weight, SAFE_WEIGHT_PER_MINUTE)
            );
        }

        long requiredTokensX1000 = (long) weight * 1000L;
        int retryCount = 0;

        while (true) {
            // 先補充令牌（基於時間流逝）
            refillTokens();

            // CAS 嘗試消耗令牌
            long current = availableTokensX1000.get();
            if (current >= requiredTokensX1000) {
                // 令牌足夠，CAS 原子扣減
                if (availableTokensX1000.compareAndSet(current, current - requiredTokensX1000)) {
                    totalWeightConsumed.addAndGet(weight);
                    log.debug("API Weight 消耗: {} | 剩餘 Weight: {:.1f} | 累計消耗: {}",
                        weight, getRemainingWeight(), totalWeightConsumed.get());
                    return;
                }
                // CAS 失敗（並發競爭），重試
                continue;
            }

            // 令牌不足，計算需等待時間
            long deficitX1000 = requiredTokensX1000 - current;
            long waitMs = (long) Math.ceil(deficitX1000 / (TOKENS_PER_MS * 1000.0));
            waitMs = Math.max(10, Math.min(waitMs, 60_000)); // 等待時間限制在 10ms ~ 60s

            throttledCount.incrementAndGet();
            retryCount++;

            log.warn(
                "API Weight 不足！需要 {} weight，當前剩餘 {:.1f}。等待 {}ms 後重試（第 {} 次）。",
                weight, getRemainingWeight(), waitMs, retryCount
            );

            // Virtual Thread 友好的睡眠（不佔用 Carrier Thread）
            Thread.sleep(waitMs);
        }
    }

    /**
     * 非阻塞嘗試消耗令牌。
     *
     * @param weight 消耗的 Weight
     * @return true 如果成功獲取令牌；false 如果當前令牌不足
     */
    public boolean tryAcquire(int weight) {
        if (weight <= 0) {
            return false;
        }

        refillTokens();

        long requiredTokensX1000 = (long) weight * 1000L;
        long current;
        do {
            current = availableTokensX1000.get();
            if (current < requiredTokensX1000) {
                return false;
            }
        } while (!availableTokensX1000.compareAndSet(current, current - requiredTokensX1000));

        totalWeightConsumed.addAndGet(weight);
        return true;
    }

    // ============================================================
    // 令牌補充（基於時間流逝的漏桶填充）
    // ============================================================

    /**
     * 根據上次補充後的時間流逝，向令牌桶補充令牌。
     * 此操作是無鎖的（使用 CAS 保證原子性）。
     */
    private void refillTokens() {
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTimeMs.get();
        long elapsedMs = now - lastRefill;

        if (elapsedMs <= 0) {
            return; // 時間未流逝（或系統時鐘回撥），直接返回
        }

        // 計算應補充的令牌數（* 1000 精度）
        long tokensToAddX1000 = (long) (elapsedMs * TOKENS_PER_MS * 1000.0);
        if (tokensToAddX1000 <= 0) {
            return;
        }

        // CAS 更新最後補充時間（多個線程競爭時，只有一個成功）
        if (lastRefillTimeMs.compareAndSet(lastRefill, now)) {
            // 更新令牌數，但不超過最大容量
            availableTokensX1000.updateAndGet(current ->
                Math.min(current + tokensToAddX1000, maxTokensX1000)
            );
        }
    }

    // ============================================================
    // 統計查詢方法
    // ============================================================

    /**
     * 獲取當前剩餘可用 Weight（估算值）。
     */
    public double getRemainingWeight() {
        refillTokens();
        return availableTokensX1000.get() / 1000.0;
    }

    /**
     * 獲取被限流的總次數。
     */
    public long getThrottledCount() {
        return throttledCount.get();
    }

    /**
     * 獲取累計消耗的總 Weight。
     */
    public long getTotalWeightConsumed() {
        return totalWeightConsumed.get();
    }
}
