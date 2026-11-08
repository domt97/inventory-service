package com.dotran.oms.inventory.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StockMovementType")
class StockMovementTypeTest {

    @Test
    @DisplayName("RECEIVE, RETURN, ADJUSTMENT_IN, TRANSFER_IN are stock-in types")
    void shouldIdentifyStockInTypes() {
        assertTrue(StockMovementType.RECEIVE.isStockIn());
        assertTrue(StockMovementType.RETURN.isStockIn());
        assertTrue(StockMovementType.ADJUSTMENT_IN.isStockIn());
        assertTrue(StockMovementType.TRANSFER_IN.isStockIn());
    }

    @Test
    @DisplayName("SALE, ADJUSTMENT_OUT, DAMAGE, TRANSFER_OUT are not stock-in types")
    void shouldRejectStockOutTypesAsStockIn() {
        assertFalse(StockMovementType.SALE.isStockIn());
        assertFalse(StockMovementType.ADJUSTMENT_OUT.isStockIn());
        assertFalse(StockMovementType.DAMAGE.isStockIn());
        assertFalse(StockMovementType.TRANSFER_OUT.isStockIn());
    }

    @Test
    @DisplayName("SALE, ADJUSTMENT_OUT, DAMAGE, TRANSFER_OUT are stock-out types")
    void shouldIdentifyStockOutTypes() {
        assertTrue(StockMovementType.SALE.isStockOut());
        assertTrue(StockMovementType.ADJUSTMENT_OUT.isStockOut());
        assertTrue(StockMovementType.DAMAGE.isStockOut());
        assertTrue(StockMovementType.TRANSFER_OUT.isStockOut());
    }

    @Test
    @DisplayName("RECEIVE, RETURN, ADJUSTMENT_IN, TRANSFER_IN are not stock-out types")
    void shouldRejectStockInTypesAsStockOut() {
        assertFalse(StockMovementType.RECEIVE.isStockOut());
        assertFalse(StockMovementType.RETURN.isStockOut());
        assertFalse(StockMovementType.ADJUSTMENT_IN.isStockOut());
        assertFalse(StockMovementType.TRANSFER_IN.isStockOut());
    }

    @Test
    @DisplayName("isStockIn and isStockOut are never both true")
    void shouldBeMutuallyExclusive() {
        for (StockMovementType type : StockMovementType.values()) {
            assertFalse(type.isStockIn() && type.isStockOut(), type + " cannot be both stock-in and stock-out");
        }
    }

    @Test
    @DisplayName("the hold types are neither stock-in nor stock-out and not manually adjustable (BR-INV-05)")
    void holdTypesAreNotOnHandChanges() {
        for (StockMovementType type : new StockMovementType[]{
                StockMovementType.RESERVE, StockMovementType.RESERVE_RELEASE, StockMovementType.RESERVE_EXPIRE}) {
            assertFalse(type.isStockIn(), type + " is not stock-in");
            assertFalse(type.isStockOut(), type + " is not stock-out");
            assertFalse(type.isManualAdjustable(), type + " is not manually adjustable");
        }
        assertEquals(StockMovementType.Effect.HOLD, StockMovementType.RESERVE.effect());
        assertEquals(StockMovementType.Effect.HOLD_RELEASE, StockMovementType.RESERVE_RELEASE.effect());
        assertEquals(StockMovementType.Effect.HOLD_RELEASE, StockMovementType.RESERVE_EXPIRE.effect());
    }

    @Test
    @DisplayName("every on-hand type is manually adjustable, SALE included")
    void onHandTypesAreManuallyAdjustable() {
        for (StockMovementType type : StockMovementType.values()) {
            assertEquals(type.isStockIn() || type.isStockOut(), type.isManualAdjustable(), type.name());
        }
        assertTrue(StockMovementType.SALE.isManualAdjustable());
    }

    @Test
    @DisplayName("STOCK_IN_TYPES and STOCK_OUT_TYPES list exactly the on-hand types")
    void typeListsFollowEffects() {
        assertEquals(java.util.List.of(StockMovementType.RECEIVE, StockMovementType.RETURN,
                StockMovementType.ADJUSTMENT_IN, StockMovementType.TRANSFER_IN), StockMovementType.STOCK_IN_TYPES);
        assertEquals(java.util.List.of(StockMovementType.SALE, StockMovementType.ADJUSTMENT_OUT,
                StockMovementType.DAMAGE, StockMovementType.TRANSFER_OUT), StockMovementType.STOCK_OUT_TYPES);
    }
}
