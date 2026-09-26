package com.dotran.oms.inventory.infrastructure.persistence.jpa;

import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataStockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    List<StockReservationEntity> findByOrderId(UUID orderId);

    List<StockReservationEntity> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant expiresAtBefore);
}
