package com.dotran.example.inventory.infrastructure.persistence.jpa;

import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataStockReservationRepository extends JpaRepository<StockReservationEntity, Long> {

    List<StockReservationEntity> findByOrderId(UUID orderId);
}
