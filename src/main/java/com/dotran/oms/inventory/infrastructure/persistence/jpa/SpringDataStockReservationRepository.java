package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.domain.enums.ReservationStatus;
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

    List<StockReservationEntity> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant expiresAtBefore);

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
