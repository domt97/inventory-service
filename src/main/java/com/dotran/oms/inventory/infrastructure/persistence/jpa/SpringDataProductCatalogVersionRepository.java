package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.infrastructure.persistence.entity.ProductCatalogVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface SpringDataProductCatalogVersionRepository extends JpaRepository<ProductCatalogVersionEntity, UUID> {

    /**
     * Version gate: inserts the first row, or moves the version forward only if it is newer and merchant/store match.
     * Returns 1 if the event should be applied, 0 otherwise. Postgres locks the conflicting row in both cases.
     */
    @Modifying
    @Query(value = """
        INSERT INTO product_catalog_versions (product_id, merchant_id, store_id, version, updated_at)
        VALUES (:productId, :merchantId, :storeId, :version, CURRENT_TIMESTAMP)
        ON CONFLICT (product_id) DO UPDATE
            SET version    = EXCLUDED.version,
                updated_at = CURRENT_TIMESTAMP
            WHERE product_catalog_versions.version     < EXCLUDED.version
              AND product_catalog_versions.merchant_id = EXCLUDED.merchant_id
              AND product_catalog_versions.store_id    = EXCLUDED.store_id
        """, nativeQuery = true)
    int advance(@Param("productId") UUID productId,
                @Param("merchantId") UUID merchantId,
                @Param("storeId") UUID storeId,
                @Param("version") long version);
}
