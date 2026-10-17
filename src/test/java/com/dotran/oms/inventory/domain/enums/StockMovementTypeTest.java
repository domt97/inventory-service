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
    @DisplayName("isStockIn and isStockOut are mutually exclusive for every type")
    void shouldBeMutuallyExclusive() {
        for (StockMovementType type : StockMovementType.values()) {
            assertNotEquals(type.isStockIn(), type.isStockOut(),
                    type + " must be either stock-in or stock-out, not both");
        }
    }
}
