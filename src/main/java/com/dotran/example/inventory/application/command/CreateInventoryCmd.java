package com.dotran.example.inventory.application.command;

import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.ProductSku;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateInventoryCmd {

    private TenantId tenantId;
    private StoreId storeId;
    private ProductId productId;
    private ProductSku sku;
}
