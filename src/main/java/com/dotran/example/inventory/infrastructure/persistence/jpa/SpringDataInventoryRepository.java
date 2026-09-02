package com.dotran.example.inventory.infrastructure.persistence.jpa;

import com.dotran.example.inventory.infrastructure.persistence.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataInventoryRepository extends JpaRepository<InventoryEntity, UUID> {

    List<InventoryEntity> findAllByStoreProductId(UUID storeProductId);
}
