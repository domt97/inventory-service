package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;

import java.util.Optional;

public interface ProductCatalogVersionRepository {

    /**
     * Records {@code version} for the product if it is newer than the stored one and merchant/store match
     * (inserts the first row). Locks the product's row either way.
     *
     * @return true if the event should be applied
     */
    boolean advance(ProductId productId, MerchantId merchantId, StoreId storeId, long version);

    Optional<ProductCatalogVersion> findByProductId(ProductId productId);

    record ProductCatalogVersion(ProductId productId, MerchantId merchantId, StoreId storeId, long version) {
    }
}
