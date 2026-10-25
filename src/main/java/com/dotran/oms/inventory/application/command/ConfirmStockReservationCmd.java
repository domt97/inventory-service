package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConfirmStockReservationCmd {

    private MerchantId merchantId;
    private StoreId storeId;

    private OrderId orderId;
}
