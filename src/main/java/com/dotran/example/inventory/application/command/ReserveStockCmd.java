package com.dotran.example.inventory.application.command;

import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.OrderItemId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReserveStockCmd {

    private TenantId tenantId;
    private StoreId storeId;

    private InventoryId inventoryId;

    private OrderId orderId;
    private OrderItemId orderItemId;

    private long quantity;
}
