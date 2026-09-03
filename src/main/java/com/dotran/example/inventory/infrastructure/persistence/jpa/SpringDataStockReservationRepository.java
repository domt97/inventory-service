package com.dotran.example.inventory.infrastructure.persistence.jpa;

import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataStockReservationRepository extends JpaRepository<StockReservationEntity, Long> {
}
