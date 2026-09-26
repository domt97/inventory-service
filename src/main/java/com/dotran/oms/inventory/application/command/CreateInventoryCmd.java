package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CreateInventoryCmd {

    private TenantId tenantId;
    private StoreId storeId;
    private ProductId productId;
    private List<SKU> skus;
}
