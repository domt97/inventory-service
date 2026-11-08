package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataStockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    /**
     * Reservations of one order with SKU and product, read past the persistence context (native guarded transitions
     * leave managed entities stale). {@code expires_at} comes back as epoch microseconds to avoid driver-dependent
     * timestamp types in a native projection.
     */
    @Query(value = """
        SELECT r.id                                                  AS id,
               r.order_item_id                                       AS "orderItemId",
               r.inventory_id                                        AS "inventoryId",
               i.store_product_id                                    AS "productId",
               i.sku                                                 AS sku,
               r.quantity                                            AS quantity,
               r.status                                              AS status,
               CAST(EXTRACT(EPOCH FROM r.expires_at) * 1000000 AS BIGINT) AS "expiresAtMicros"
        FROM stock_reservations r
        JOIN inventories i ON i.id = r.inventory_id
        WHERE r.order_id = :orderId
          AND r.merchant_id = :merchantId
          AND r.store_id = :storeId
        ORDER BY r.id
        """, nativeQuery = true)
    List<LineView> findLineViews(@Param("merchantId") UUID merchantId,
                                 @Param("storeId") UUID storeId,
                                 @Param("orderId") UUID orderId);

    /** Projection of {@link #findLineViews}. */
    interface LineView {
        Long getId();

        UUID getOrderItemId();

        UUID getInventoryId();

        UUID getProductId();

        String getSku();

        Long getQuantity();

        String getStatus();

        Long getExpiresAtMicros();
    }

    // status stays a literal: the partial index idx_stock_reservation_expiry (WHERE status = 'RESERVED') is only
    // usable when the planner can prove the predicate, which a bound parameter prevents once pgjdbc uses a generic plan.
    @Query(value = """
        SELECT *
        FROM stock_reservations
        WHERE status = 'RESERVED'
          AND expires_at < :expiresAtBefore
          AND (expires_at, id) > (:afterExpiresAt, :afterId)
        ORDER BY expires_at, id
        LIMIT :limit
        """, nativeQuery = true)
    List<StockReservationEntity> findExpiredAfter(@Param("expiresAtBefore") Instant expiresAtBefore,
                                                  @Param("afterExpiresAt") Instant afterExpiresAt,
                                                  @Param("afterId") long afterId,
                                                  @Param("limit") int limit);

    @Modifying
    @Query(value = """
        UPDATE stock_reservations
        SET
            status     = :toStatus,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = :id
          AND status = :fromStatus
        """, nativeQuery = true)
    int transitionStatus(@Param("id") long id,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus);
}
