package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StockMovement")
class StockMovementTest {

    private Inventory inventory;

    @BeforeEach
    void setUp() {
        MerchantId merchantId = MerchantId.of(UUID.randomUUID());
        StoreId storeId = StoreId.of(UUID.randomUUID());

        inventory = Inventory.builder()
                .id(InventoryId.of(UUID.randomUUID()))
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("newInstance copies merchant, store and inventory context from inventory")
    void shouldCopyContextFromInventory() {
        StockMovement movement = StockMovement.newInstance(
                inventory, StockMovementType.ADJUSTMENT_IN, 50L, "ORDER", null, "Test reason");

        assertEquals(inventory.getMerchantId(), movement.getMerchantId());
        assertEquals(inventory.getStoreId(), movement.getStoreId());
        assertEquals(inventory.getId(), movement.getInventoryId());
    }

    @Test
    @DisplayName("newInstance sets movement type, quantity and reason")
    void shouldSetMovementFields() {
        StockMovement movement = StockMovement.newInstance(
                inventory, StockMovementType.SALE, 30L, "ORDER", null, "sale reason");

        assertEquals(StockMovementType.SALE, movement.getType());
        assertEquals(30L, movement.getQuantity());
        assertEquals("sale reason", movement.getReason());
        assertEquals("ORDER", movement.getReferenceType());
    }

    @Test
    @DisplayName("newInstance sets createdAt timestamp")
    void shouldSetCreatedAt() {
        StockMovement movement = StockMovement.newInstance(
                inventory, StockMovementType.RECEIVE, 10L, null, null, null);

        assertNotNull(movement.getCreatedAt());
    }
}
