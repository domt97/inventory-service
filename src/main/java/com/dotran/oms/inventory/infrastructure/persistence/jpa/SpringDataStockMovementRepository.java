package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.infrastructure.persistence.entity.StockMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SpringDataStockMovementRepository extends JpaRepository<StockMovementEntity, Long> {

    List<StockMovementEntity> findAllByInventoryId(UUID inventoryId);

    /**
     * One movement per reservation now in {@code status}: reference {@code StockMovement.REFERENCE_TYPE_ORDER_ITEM} / order item id, {@code reservation_id}
     * set (BR-INV-05). Duplicates of (reservation, type) are skipped. Callers never pass an empty list.
     * {@code reason} may be null: the cast types the parameter whatever JDBC type the null is bound with.
     */
    @Modifying
    @Query(value = """
        INSERT INTO stock_movements (merchant_id, store_id, inventory_id, type, quantity,
                                     reference_type, reference_id, reservation_id, reason, created_at)
        SELECT r.merchant_id, r.store_id, r.inventory_id, :type, r.quantity,
               CAST(:referenceType AS VARCHAR), r.order_item_id, r.id, CAST(:reason AS VARCHAR), CURRENT_TIMESTAMP
        FROM stock_reservations r
        WHERE r.id IN (:reservationIds)
          AND r.status = :status
        ON CONFLICT (reservation_id, type) WHERE reservation_id IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    int insertForReservations(@Param("reservationIds") Collection<Long> reservationIds,
                              @Param("type") String type,
                              @Param("referenceType") String referenceType,
                              @Param("status") String status,
                              @Param("reason") String reason);
}
