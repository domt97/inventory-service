package com.dotran.example.inventory.domain.model;

import com.dotran.example.inventory.common.domain.BaseDomain;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ReferenceId;
import com.dotran.example.inventory.common.domain.valueobject.StockMovementId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.enums.StockMovementType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class StockMovement extends BaseDomain<StockMovementId> {

    private TenantId tenantId;
    private StoreId storeId;

    private InventoryId inventoryId;

    private StockMovementType type;

    private Long quantity;

    private String referenceType;
    private ReferenceId referenceId;

    private String reason;

    private Instant createdAt;

    public static StockMovement newInstance(Inventory inventory, StockMovementType type, Long quantity, String reason) {
        StockMovement stockMovement = StockMovement.builder()
                .tenantId(inventory.getTenantId())
                .storeId(inventory.getStoreId())
                .inventoryId(inventory.getId())
                .type(type)
                .quantity(quantity)
                .reason(reason)
                .build();
        stockMovement.newStockMovement();

        return stockMovement;
    }

    public void newStockMovement() {
        this.createdAt = Instant.now();
    }
}
