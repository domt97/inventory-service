package com.dotran.example.inventory.application.command;

import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.OrderItemId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
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
