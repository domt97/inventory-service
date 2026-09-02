package com.dotran.example.inventory.application.command;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.enums.StockMovementType;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdjustStockCmd {

    private TenantId tenantId;
    private StoreId storeId;
    private InventoryId inventoryId;
    @Positive
    private Long quantity;
    private StockMovementType movementType;
    private String reason;
}
