package com.dotran.oms.inventory.application.command;

import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
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
