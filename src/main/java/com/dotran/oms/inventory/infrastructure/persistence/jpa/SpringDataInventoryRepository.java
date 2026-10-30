package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.infrastructure.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SpringDataInventoryRepository extends JpaRepository<InventoryEntity, UUID> {

    List<InventoryEntity> findAllByStoreProductId(UUID storeProductId);

    List<InventoryEntity> findAllByStoreIdAndSkuIn(UUID storeId, Collection<String> skus);

    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            reserved_quantity = reserved_quantity + :quantity,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = :inventoryId
          AND (quantity - reserved_quantity) >= :quantity
        """, nativeQuery = true)
    int reserve(@Param("inventoryId") UUID inventoryId,
                @Param("quantity") long quantity);

    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            reserved_quantity = reserved_quantity - :quantity,
            quantity          = quantity - :quantity,
            updated_at        = CURRENT_TIMESTAMP
        WHERE id = :inventoryId
          AND reserved_quantity >= :quantity
        """, nativeQuery = true)
    int confirm(@Param("inventoryId") UUID inventoryId,
                @Param("quantity") long quantity);

    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            reserved_quantity = reserved_quantity - :quantity,
            updated_at        = CURRENT_TIMESTAMP
        WHERE id = :inventoryId
          AND reserved_quantity >= :quantity
        """, nativeQuery = true)
    int release(@Param("inventoryId") UUID inventoryId,
                @Param("quantity") long quantity);

    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            quantity   = quantity + :quantity,
            updated_at = CURRENT_TIMESTAMP
        WHERE id      = :inventoryId
          AND merchant_id = :merchantId
          AND store_id  = :storeId
        """, nativeQuery = true)
    int stockIn(@Param("inventoryId") UUID inventoryId,
                @Param("merchantId") UUID merchantId,
                @Param("storeId") UUID storeId,
                @Param("quantity") long quantity);

    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            quantity   = quantity - :quantity,
            updated_at = CURRENT_TIMESTAMP
        WHERE id      = :inventoryId
          AND merchant_id = :merchantId
          AND store_id  = :storeId
          AND (quantity - reserved_quantity) >= :quantity
        """, nativeQuery = true)
    int stockOut(@Param("inventoryId") UUID inventoryId,
                 @Param("merchantId") UUID merchantId,
                 @Param("storeId") UUID storeId,
                 @Param("quantity") long quantity);

    /** Catalog sync: creates a row with no stock unless (store_id, sku) exists. 1 = inserted, 0 = already there. */
    @Modifying
    @Query(value = """
        INSERT INTO inventories (id, merchant_id, store_id, store_product_id, sku,
                                 quantity, reserved_quantity, status, created_at, updated_at)
        VALUES (:id, :merchantId, :storeId, :productId, :sku, 0, 0, :status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (store_id, sku) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("merchantId") UUID merchantId,
                       @Param("storeId") UUID storeId,
                       @Param("productId") UUID productId,
                       @Param("sku") String sku,
                       @Param("status") String status);

    /** Catalog sync: guarded status change of one row of the product; 0 if it already has the status. */
    @Modifying
    @Query(value = """
        UPDATE inventories
        SET
            status     = :status,
            updated_at = CURRENT_TIMESTAMP
        WHERE id               = :inventoryId
          AND merchant_id      = :merchantId
          AND store_id         = :storeId
          AND store_product_id = :productId
          AND status          <> :status
        """, nativeQuery = true)
    int updateStatus(@Param("inventoryId") UUID inventoryId,
                     @Param("merchantId") UUID merchantId,
                     @Param("storeId") UUID storeId,
                     @Param("productId") UUID productId,
                     @Param("status") String status);
}
