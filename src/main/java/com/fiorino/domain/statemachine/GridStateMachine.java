package com.fiorino.domain.statemachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================
 * GridStateMachine — Bot 全局狀態機（Domain Layer）
 * ============================================================
 *
 * 架構設計思維：
 * 本類管理整個機器人的「宏觀生命週期」，與 GridCell 的「微觀訂單狀態」
 * 嚴格分離。採用「雙層狀態機」設計：
 *
 *   L1 宏觀：GridStateMachine（本類）— Bot 是否在運行？
 *   L2 微觀：GridCell 狀態機 — 每個網格格子的訂單生命週期
 *
 * 並發設計：
 * - BotState 使用 AtomicReference 保證可見性，但狀態「轉移」本身
 *   需要 ReentrantLock 保護（防止並發轉移導致的競態條件）。
 * - 例如：不允許兩個線程同時執行 RUNNING → PAUSED 和 RUNNING → CRASHED_RECOVERING
 *
 * 狀態轉移圖：
 *
 *   ┌──────┐     start()    ┌─────────┐
 *   │ INIT │ ─────────────► │ RUNNING │◄──────────────┐
 *   └──────┘                └─────────┘               │
 *                                │                     │
 *                    pause()     │   networkError()    │ recover()
 *                                ▼                     │
 *                           ┌────────┐                 │
 *                           │ PAUSED │                 │
 *                           └────────┘                 │
 *                                                      │
 *                           ┌───────────────────────┐  │
 *                           │   CRASHED_RECOVERING  │──┘
 *                           └───────────────────────┘
 *
 * @author benson-code
 * @version 1.0.0
 */
public final class GridStateMachine {

    private static final Logger log = LoggerFactory.getLogger(GridStateMachine.class);

    // ============================================================
    // 狀態枚舉定義
    // ============================================================

    /**
     * Bot 全局運行狀態：
     *
     * INIT                — 初始化中，尚未開始網格策略
     * RUNNING             — 正常運行中，網格策略活躍
     * PAUSED              — 暫停（用戶手動暫停或臨時風控觸發）
     * CRASHED_RECOVERING  — 系統崩潰後重啟，正在從本地快照恢復，
     *                       比對 Binance 訂單狀態，進行差異對賬
     * STOPPED             — 完全停止，資源已釋放
     */
    public enum BotState {
        INIT,
        RUNNING,
        PAUSED,
        CRASHED_RECOVERING,
        STOPPED
    }

    // ============================================================
    // 核心欄位
    // ============================================================

    /** 當前 Bot 狀態（AtomicReference 保證跨線程可見性） */
    private final AtomicReference<BotState> currentState;

    /**
     * 狀態轉移鎖：
     * 確保任何時刻只有一個線程在執行狀態轉移邏輯。
     * 使用 ReentrantLock 而非 synchronized，支援 tryLock() 非阻塞嘗試。
     */
    private final ReentrantLock transitionLock = new ReentrantLock();

    /** 狀態機建立時間 */
    private final Instant createdAt;

    /** 最後一次狀態變更時間 */
    private volatile Instant lastTransitionAt;

    /** 最後一次崩潰的錯誤訊息（用於診斷） */
    private volatile String lastCrashReason;

    /** 累計崩潰次數（用於風控：崩潰次數過多時觸發熔斷） */
    private volatile int crashCount;

    /** 崩潰次數超過此閾值時，拒絕自動恢復，需人工介入 */
    private static final int MAX_AUTO_RECOVER_ATTEMPTS = 5;

    // ============================================================
    // 構造器
    // ============================================================

    /**
     * 創建一個新的狀態機，初始狀態為 INIT。
     */
    public GridStateMachine() {
        this.currentState = new AtomicReference<>(BotState.INIT);
        this.createdAt = Instant.now();
        this.lastTransitionAt = Instant.now();
        this.lastCrashReason = null;
        this.crashCount = 0;

        log.info("GridStateMachine 初始化完成，初始狀態: {}", BotState.INIT);
    }

    /**
     * 帶有初始狀態的構造器（用於從持久化狀態恢復）。
     *
     * @param restoredState 從 DB 恢復的狀態
     */
    public GridStateMachine(BotState restoredState) {
        Objects.requireNonNull(restoredState, "恢復狀態不能為 null");
        this.currentState = new AtomicReference<>(restoredState);
        this.createdAt = Instant.now();
        this.lastTransitionAt = Instant.now();
        this.lastCrashReason = null;
        this.crashCount = 0;

        log.warn("GridStateMachine 從恢復狀態啟動: {}", restoredState);
    }

    // ============================================================
    // 狀態轉移方法
    // ============================================================

    /**
     * 【轉移】INIT → RUNNING
     * 啟動網格策略，開始掛單循環。
     *
     * @throws IllegalStateException 如果當前狀態不允許此轉移
     */
    public void start() {
        transitionLock.lock();
        try {
            BotState current = currentState.get();
            if (!Objects.equals(BotState.INIT, current) &&
                !Objects.equals(BotState.PAUSED, current)) {
                throw new IllegalStateException(
                    String.format("無法從狀態 %s 轉移到 RUNNING，只允許從 INIT 或 PAUSED 啟動", current)
                );
            }
            doTransition(current, BotState.RUNNING, "用戶啟動");
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * 【轉移】RUNNING → PAUSED
     * 暫停網格策略（不撤銷現有掛單，只停止新建訂單）。
     *
     * @param reason 暫停原因（用於日誌記錄）
     */
    public void pause(String reason) {
        transitionLock.lock();
        try {
            BotState current = currentState.get();
            if (!Objects.equals(BotState.RUNNING, current)) {
                log.warn("嘗試暫停，但當前狀態為 {}，跳過轉移", current);
                return;
            }
            doTransition(current, BotState.PAUSED, Objects.requireNonNullElse(reason, "未指定原因"));
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * 【轉移】任意狀態 → CRASHED_RECOVERING
     * 系統偵測到嚴重錯誤（網路斷線、API 異常），進入恢復流程。
     *
     * @param crashReason 崩潰原因描述
     * @return true 如果可以自動恢復；false 如果崩潰次數超限，需人工介入
     */
    public boolean enterCrashRecovery(String crashReason) {
        transitionLock.lock();
        try {
            // 崩潰次數超限：熔斷保護，拒絕自動恢復
            if (crashCount >= MAX_AUTO_RECOVER_ATTEMPTS) {
                log.error(
                    "崩潰次數已達上限 {}，拒絕自動恢復。原因: {}。請人工介入！",
                    MAX_AUTO_RECOVER_ATTEMPTS, crashReason
                );
                doTransition(currentState.get(), BotState.STOPPED, "崩潰次數超限，熔斷停止");
                return false;
            }

            BotState current = currentState.get();
            if (Objects.equals(BotState.STOPPED, current)) {
                log.warn("Bot 已停止，不進行崩潰恢復");
                return false;
            }

            this.lastCrashReason = Objects.requireNonNullElse(crashReason, "未知原因");
            this.crashCount++;

            log.error("Bot 進入崩潰恢復模式，第 {}/{} 次。原因: {}",
                crashCount, MAX_AUTO_RECOVER_ATTEMPTS, this.lastCrashReason);

            doTransition(current, BotState.CRASHED_RECOVERING, crashReason);
            return true;
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * 【轉移】CRASHED_RECOVERING → RUNNING
     * 恢復完成，重新進入運行狀態。
     *
     * @throws IllegalStateException 如果當前不在恢復狀態
     */
    public void recover() {
        transitionLock.lock();
        try {
            BotState current = currentState.get();
            if (!Objects.equals(BotState.CRASHED_RECOVERING, current)) {
                throw new IllegalStateException(
                    String.format("無法恢復：當前狀態為 %s，必須為 CRASHED_RECOVERING", current)
                );
            }
            doTransition(current, BotState.RUNNING, "崩潰恢復成功");
            log.info("Bot 成功從崩潰狀態恢復，繼續運行。累計崩潰次數: {}", crashCount);
        } finally {
            transitionLock.unlock();
        }
    }

    /**
     * 【轉移】任意狀態 → STOPPED
     * 完全停止 Bot（優雅退出）。
     *
     * @param reason 停止原因
     */
    public void stop(String reason) {
        transitionLock.lock();
        try {
            BotState current = currentState.get();
            if (Objects.equals(BotState.STOPPED, current)) {
                log.warn("Bot 已經處於 STOPPED 狀態，忽略重複停止請求");
                return;
            }
            doTransition(current, BotState.STOPPED, Objects.requireNonNullElse(reason, "正常停止"));
        } finally {
            transitionLock.unlock();
        }
    }

    // ============================================================
    // 查詢方法（無需加鎖，AtomicReference 保證可見性）
    // ============================================================

    /**
     * 獲取當前狀態。線程安全，直接讀取 AtomicReference。
     */
    public BotState getCurrentState() {
        return currentState.get();
    }

    /**
     * 判斷 Bot 是否正在運行（可以執行訂單操作）。
     */
    public boolean isRunning() {
        return Objects.equals(BotState.RUNNING, currentState.get());
    }

    /**
     * 判斷 Bot 是否已完全停止。
     */
    public boolean isStopped() {
        return Objects.equals(BotState.STOPPED, currentState.get());
    }

    public Instant getLastTransitionAt() {
        return lastTransitionAt;
    }

    public String getLastCrashReason() {
        return lastCrashReason;
    }

    public int getCrashCount() {
        return crashCount;
    }

    // ============================================================
    // 私有工具方法
    // ============================================================

    /**
     * 執行狀態轉移，更新時間戳並記錄日誌。
     * 此方法僅在 transitionLock 持有時調用。
     */
    private void doTransition(BotState from, BotState to, String reason) {
        currentState.set(to);
        this.lastTransitionAt = Instant.now();
        log.info("狀態轉移: {} → {} | 原因: {}", from, to, reason);
    }
}
