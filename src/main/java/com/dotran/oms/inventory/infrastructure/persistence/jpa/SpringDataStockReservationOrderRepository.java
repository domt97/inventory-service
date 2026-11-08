package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code restock} may be null; {@code CAST(CAST(:restock AS TEXT) AS BOOLEAN)} accepts the null whatever JDBC type it is
 * bound with (an untyped null may arrive as bytea).
 */
public interface SpringDataStockReservationOrderRepository extends JpaRepository<StockReservationOrderEntity, UUID> {

    /** 1 if the header was created, 0 if one already existed (any merchant/store). A new header has no rejection. */
    @Modifying
    @Query(value = """
        INSERT INTO stock_reservation_orders (order_id, merchant_id, store_id, status, restock, created_at, updated_at)
        VALUES (:orderId, :merchantId, :storeId, :status, CAST(CAST(:restock AS TEXT) AS BOOLEAN),
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (order_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId,
                       @Param("merchantId") UUID merchantId,
                       @Param("storeId") UUID storeId,
                       @Param("status") String status,
                       @Param("restock") Boolean restock);

    /** Locks the header of this order if it belongs to this merchant and store. */
    @Query(value = """
        SELECT o.order_id AS "orderId", o.merchant_id AS "merchantId", o.store_id AS "storeId",
               o.status AS status, o.restock AS restock, CAST(o.rejection AS TEXT) AS rejection
        FROM stock_reservation_orders o
        WHERE o.order_id = :orderId AND o.merchant_id = :merchantId AND o.store_id = :storeId
        FOR UPDATE
        """, nativeQuery = true)
    Optional<HeaderView> lockForUpdate(@Param("orderId") UUID orderId,
                                       @Param("merchantId") UUID merchantId,
                                       @Param("storeId") UUID storeId);

    /** Status and restock; the rejection is left as it is. 0 if the status is no longer {@code expectedStatus}. */
    @Modifying
    @Query(value = """
        UPDATE stock_reservation_orders
        SET status = :status, restock = CAST(CAST(:restock AS TEXT) AS BOOLEAN), updated_at = CURRENT_TIMESTAMP
        WHERE order_id = :orderId AND merchant_id = :merchantId AND store_id = :storeId
          AND status = :expectedStatus
        """, nativeQuery = true)
    int update(@Param("orderId") UUID orderId,
               @Param("merchantId") UUID merchantId,
               @Param("storeId") UUID storeId,
               @Param("status") String status,
               @Param("restock") Boolean restock,
               @Param("expectedStatus") String expectedStatus);

    /** Same as {@link #update} and also writes the rejection (never null here, so the JSONB cast has a typed value). */
    @Modifying
    @Query(value = """
        UPDATE stock_reservation_orders
        SET status = :status, restock = CAST(CAST(:restock AS TEXT) AS BOOLEAN), rejection = CAST(:rejection AS jsonb),
            updated_at = CURRENT_TIMESTAMP
        WHERE order_id = :orderId AND merchant_id = :merchantId AND store_id = :storeId
          AND status = :expectedStatus
        """, nativeQuery = true)
    int updateWithRejection(@Param("orderId") UUID orderId,
                            @Param("merchantId") UUID merchantId,
                            @Param("storeId") UUID storeId,
                            @Param("status") String status,
                            @Param("restock") Boolean restock,
                            @Param("rejection") String rejection,
                            @Param("expectedStatus") String expectedStatus);

    interface HeaderView {
        UUID getOrderId();

        UUID getMerchantId();

        UUID getStoreId();

        String getStatus();

        Boolean getRestock();

        String getRejection();
    }
}
