package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository.ProductCatalogVersion;
import com.dotran.oms.inventory.infrastructure.persistence.entity.ProductCatalogVersionEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataProductCatalogVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCatalogVersionPersistenceAdapter")
class ProductCatalogVersionPersistenceAdapterTest {

    @Mock
    private SpringDataProductCatalogVersionRepository repository;

    @InjectMocks
    private ProductCatalogVersionPersistenceAdapter adapter;

    private final ProductId productId = ProductId.newProductId();
    private final MerchantId merchantId = MerchantId.newMerchantId();
    private final StoreId storeId = StoreId.newStoreId();

    @Test
    @DisplayName("advance is true when the upsert changed a row, false when the gate kept it")
    void advance_mapsRowCount() {
        when(repository.advance(productId.getValue(), merchantId.getValue(), storeId.getValue(), 2L)).thenReturn(1);
        when(repository.advance(productId.getValue(), merchantId.getValue(), storeId.getValue(), 1L)).thenReturn(0);

        assertThat(adapter.advance(productId, merchantId, storeId, 2L)).isTrue();
        assertThat(adapter.advance(productId, merchantId, storeId, 1L)).isFalse();
    }

    @Test
    @DisplayName("findByProductId maps the stored row")
    void findByProductId_maps() {
        ProductCatalogVersionEntity entity = new ProductCatalogVersionEntity();
        entity.setProductId(productId.getValue());
        entity.setMerchantId(merchantId.getValue());
        entity.setStoreId(storeId.getValue());
        entity.setVersion(7L);
        when(repository.findById(productId.getValue())).thenReturn(Optional.of(entity));

        assertThat(adapter.findByProductId(productId))
                .contains(new ProductCatalogVersion(productId, merchantId, storeId, 7L));
    }
}
