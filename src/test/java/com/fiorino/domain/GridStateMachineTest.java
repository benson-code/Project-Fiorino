package com.fiorino.domain;

import com.fiorino.domain.statemachine.GridStateMachine;
import com.fiorino.domain.statemachine.GridStateMachine.BotState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GridStateMachine Unit Tests
 * Verifies bot-level state transitions and circuit breaker logic.
 */
class GridStateMachineTest {

    private GridStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new GridStateMachine();
    }

    @Test
    @DisplayName("Initial state should be INIT")
    void testInitialState() {
        assertEquals(BotState.INIT, stateMachine.getCurrentState());
        assertFalse(stateMachine.isRunning());
        assertFalse(stateMachine.isStopped());
    }

    @Test
    @DisplayName("INIT -> RUNNING via start()")
    void testStartFromInit() {
        stateMachine.start();
        assertEquals(BotState.RUNNING, stateMachine.getCurrentState());
        assertTrue(stateMachine.isRunning());
    }

    @Test
    @DisplayName("RUNNING -> PAUSED via pause()")
    void testPause() {
        stateMachine.start();
        stateMachine.pause("Test pause");
        assertEquals(BotState.PAUSED, stateMachine.getCurrentState());
        assertFalse(stateMachine.isRunning());
    }

    @Test
    @DisplayName("PAUSED -> RUNNING via start()")
    void testRestartFromPaused() {
        stateMachine.start();
        stateMachine.pause("Test");
        stateMachine.start();
        assertEquals(BotState.RUNNING, stateMachine.getCurrentState());
        assertTrue(stateMachine.isRunning());
    }

    @Test
    @DisplayName("RUNNING -> CRASHED_RECOVERING via enterCrashRecovery()")
    void testCrashRecovery() {
        stateMachine.start();
        boolean canRecover = stateMachine.enterCrashRecovery("Test crash");
        assertTrue(canRecover, "Should be able to recover on first crash");
        assertEquals(BotState.CRASHED_RECOVERING, stateMachine.getCurrentState());
        assertEquals(1, stateMachine.getCrashCount());
        assertNotNull(stateMachine.getLastCrashReason());
    }

    @Test
    @DisplayName("CRASHED_RECOVERING -> RUNNING via recover()")
    void testRecover() {
        stateMachine.start();
        stateMachine.enterCrashRecovery("Test crash");
        stateMachine.recover();
        assertEquals(BotState.RUNNING, stateMachine.getCurrentState());
        assertTrue(stateMachine.isRunning());
    }

    @Test
    @DisplayName("Circuit breaker: after max crash attempts, stops instead of recovering")
    void testCircuitBreaker() {
        stateMachine.start();

        // MAX_AUTO_RECOVER_ATTEMPTS = 5
        // Circuit breaker triggers when crashCount >= 5 (i.e., on the 6th crash attempt)
        for (int i = 0; i < 5; i++) {
            boolean canRecover = stateMachine.enterCrashRecovery("Crash " + i);
            assertTrue(canRecover, "Should be able to recover on crash " + (i+1));
            stateMachine.recover();
        }

        // 6th crash should trigger circuit breaker (crashCount = 5 >= MAX = 5)
        boolean canRecover = stateMachine.enterCrashRecovery("Final crash");
        assertFalse(canRecover, "Should NOT be able to auto-recover after max attempts");
        assertEquals(BotState.STOPPED, stateMachine.getCurrentState());
        assertTrue(stateMachine.isStopped());
    }

    @Test
    @DisplayName("Cannot stop an already stopped bot")
    void testStopIdempotent() {
        stateMachine.start();
        stateMachine.stop("Test stop");
        assertTrue(stateMachine.isStopped());

        // Should not throw, should be idempotent
        assertDoesNotThrow(() -> stateMachine.stop("Second stop"));
        assertTrue(stateMachine.isStopped());
    }

    @Test
    @DisplayName("Cannot start from STOPPED state")
    void testCannotStartFromStopped() {
        stateMachine.start();
        stateMachine.stop("Test");
        assertThrows(IllegalStateException.class, () -> stateMachine.start());
    }
}
