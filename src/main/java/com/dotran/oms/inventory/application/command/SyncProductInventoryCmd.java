package com.dotran.oms.inventory.application.command;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.ProductSkuStatus;
import com.dotran.oms.inventory.domain.enums.ProductStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Catalog snapshot of one product, from ProductCreated (version 0) or ProductUpdated (version >= 1). */
@Data
@Builder
public class SyncProductInventoryCmd {

    public enum Source { PRODUCT_CREATED, PRODUCT_UPDATED }

    private Source source;
    private MerchantId merchantId;
    private StoreId storeId;
    private ProductId productId;
    private Long version;
    private ProductStatus productStatus;
    private List<SkuSnapshot> skus;

    public record SkuSnapshot(SKU sku, ProductSkuStatus status) {
    }
}
