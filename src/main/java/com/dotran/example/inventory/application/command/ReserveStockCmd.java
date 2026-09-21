package com.dotran.example.inventory.application.command;

import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Data
@Builder
public class ReserveStockCmd {

    private TenantId tenantId;
    private StoreId storeId;
    private OrderId orderId;
    private List<ReserveStockSKU> skus;

    @Builder
    @Getter
    @Setter
    public static class ReserveStockSKU {

        private OrderItemId orderItemId;
        private ProductId productId;
        private SKU sku;
        private long quantity;
    }
}
