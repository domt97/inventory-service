package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataProductCatalogVersionRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@PersistenceAdapter
@RequiredArgsConstructor
public class ProductCatalogVersionPersistenceAdapter implements ProductCatalogVersionRepository {

    private final SpringDataProductCatalogVersionRepository repository;

    @Override
    public boolean advance(ProductId productId, MerchantId merchantId, StoreId storeId, long version) {
        return repository.advance(productId.getValue(), merchantId.getValue(), storeId.getValue(), version) > 0;
    }

    @Override
    public Optional<ProductCatalogVersion> findByProductId(ProductId productId) {
        return repository.findById(productId.getValue())
                .map(entity -> new ProductCatalogVersion(ProductId.of(entity.getProductId()),
                        MerchantId.of(entity.getMerchantId()), StoreId.of(entity.getStoreId()), entity.getVersion()));
    }
}
