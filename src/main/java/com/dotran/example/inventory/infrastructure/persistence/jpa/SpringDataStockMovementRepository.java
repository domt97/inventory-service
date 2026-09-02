package com.dotran.example.inventory.infrastructure.persistence.jpa;

import com.dotran.example.inventory.infrastructure.persistence.entity.StockMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataStockMovementRepository extends JpaRepository<StockMovementEntity, Long> {

    List<StockMovementEntity> findAllByInventoryId(UUID inventoryId);
}
