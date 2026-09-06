package com.dotran.example.inventory.application.command;

import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConfirmStockReservationCmd {

    private TenantId tenantId;
    private StoreId storeId;

    private OrderId orderId;
}
