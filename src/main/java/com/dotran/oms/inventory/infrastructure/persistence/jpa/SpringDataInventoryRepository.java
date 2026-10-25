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
}
