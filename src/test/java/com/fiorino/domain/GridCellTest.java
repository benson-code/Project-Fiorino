package com.fiorino.domain;

import com.fiorino.domain.model.GridCell;
import com.fiorino.domain.model.GridCell.CellStatus;
import com.fiorino.domain.model.GridCell.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GridCell Unit Tests
 * Verifies state machine transitions, direction update, and concurrency safety.
 */
class GridCellTest {

    private GridCell buyCell;
    private GridCell sellCell;

    @BeforeEach
    void setUp() {
        buyCell = new GridCell(0, new BigDecimal("70000"), new BigDecimal("0.001"), OrderSide.BUY);
        sellCell = new GridCell(5, new BigDecimal("75000"), new BigDecimal("0.001"), OrderSide.SELL);
    }

    @Test
    @DisplayName("Initial state should be EMPTY")
    void testInitialState() {
        assertEquals(CellStatus.EMPTY, buyCell.getStatus());
        assertEquals(OrderSide.BUY, buyCell.getOrderSide());
        assertEquals(0, buyCell.getCellIndex());
        assertEquals(new BigDecimal("70000"), buyCell.getTriggerPrice());
        assertEquals(new BigDecimal("0.001"), buyCell.getOrderQuantity());
        assertFalse(buyCell.hasActiveOrder());
        assertTrue(buyCell.needsOrder()); // EMPTY cell needs an order
    }

    @Test
    @DisplayName("State transition EMPTY -> PENDING -> ACTIVE")
    void testTransitionToActive() {
        buyCell.transitionToPending();
        assertEquals(CellStatus.PENDING, buyCell.getStatus());
        assertFalse(buyCell.needsOrder(), "PENDING cell should not need new order");

        buyCell.transitionToActive("12345");
        assertEquals(CellStatus.ACTIVE, buyCell.getStatus());
        assertEquals("12345", buyCell.getOrderId());
        assertTrue(buyCell.hasActiveOrder());
    }

    @Test
    @DisplayName("State transition ACTIVE -> FILLED")
    void testTransitionToFilled() {
        buyCell.transitionToPending();
        buyCell.transitionToActive("12345");

        BigDecimal fillQty = new BigDecimal("0.001");
        BigDecimal fillPrice = new BigDecimal("69900");
        BigDecimal pnl = new BigDecimal("1.6");

        buyCell.transitionToFilled(fillQty, fillPrice, pnl);
        assertEquals(CellStatus.FILLED, buyCell.getStatus());
        assertEquals(fillQty, buyCell.getFilledQuantity());
    }

    @Test
    @DisplayName("Reset to EMPTY should clear order ID and quantities")
    void testResetToEmpty() {
        buyCell.transitionToPending();
        buyCell.transitionToActive("12345");
        buyCell.resetToEmpty();

        assertEquals(CellStatus.EMPTY, buyCell.getStatus());
        assertNull(buyCell.getOrderId());
        assertEquals(BigDecimal.ZERO, buyCell.getFilledQuantity());
        assertTrue(buyCell.needsOrder());
    }

    @Test
    @DisplayName("updateOrderSide should change direction correctly")
    void testUpdateOrderSide() {
        assertEquals(OrderSide.BUY, buyCell.getOrderSide());
        buyCell.updateOrderSide(OrderSide.SELL);
        assertEquals(OrderSide.SELL, buyCell.getOrderSide());

        assertEquals(OrderSide.SELL, sellCell.getOrderSide());
        sellCell.updateOrderSide(OrderSide.BUY);
        assertEquals(OrderSide.BUY, sellCell.getOrderSide());
    }

    @Test
    @DisplayName("updateOrderSide should throw on null")
    void testUpdateOrderSideNull() {
        assertThrows(NullPointerException.class, () -> buyCell.updateOrderSide(null));
    }

    @Test
    @DisplayName("Cannot transition from EMPTY directly to ACTIVE (must go through PENDING)")
    void testInvalidTransitionEmptyToActive() {
        assertThrows(IllegalStateException.class, () -> buyCell.transitionToActive("12345"));
    }

    @Test
    @DisplayName("Cell with active order should report hasActiveOrder=true")
    void testHasActiveOrder() {
        assertFalse(buyCell.hasActiveOrder());
        buyCell.transitionToPending();
        assertFalse(buyCell.hasActiveOrder()); // PENDING has no order yet
        buyCell.transitionToActive("99999");
        assertTrue(buyCell.hasActiveOrder());
    }

    @Test
    @DisplayName("Transition to CANCELLED")
    void testTransitionToCancelled() {
        buyCell.transitionToPending();
        buyCell.transitionToActive("12345");
        buyCell.transitionToCancelled();
        assertEquals(CellStatus.CANCELLED, buyCell.getStatus());
        assertFalse(buyCell.hasActiveOrder());
    }
}
