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

    List<StockReservationEntity> findByOrderId(UUID orderId);

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
