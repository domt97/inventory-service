package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.AdjustStockCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.mapper.InventoryMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.repository.StockMovementRepository;
import com.dotran.example.inventory.application.repository.TenantRepository;
import com.dotran.example.inventory.application.usecase.inventory.AdjustStockUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.domain.exception.ValidationException;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.domain.model.StockMovement;
import com.dotran.example.inventory.domain.model.TenantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class AdjustStockService implements AdjustStockUseCase {

    private final InventoryRepository repository;
    private final StockMovementRepository stockMovementRepository;
    private final TenantRepository tenantRepository;
    private final InventoryMapper inventoryMapper;

    @Override
    @Transactional
    public InventoryDetailDto adjust(AdjustStockCmd cmd) {
        TenantInfo tenantInfo = tenantRepository.findByTenantId(cmd.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));

        Inventory inventory = repository.getById(cmd.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        if (!inventory.getTenantId().equals(tenantInfo.getId()) || !inventory.getStoreId().equals(cmd.getStoreId())) {
            throw new ValidationException("Inventory does not belong to the specified tenant or store");
        }

        inventory.adjust(cmd.getMovementType(), cmd.getQuantity());

        StockMovement newStockMovement = StockMovement.newInstance(
                inventory,
                cmd.getMovementType(),
                cmd.getQuantity(),
                cmd.getReason()
        );

        Inventory savedInventory = repository.save(inventory);
        StockMovement savedStockMovement = stockMovementRepository.create(newStockMovement);

        log.info("Inventory updated: {}, type: {}, quantity: {}",
                savedInventory.getId(), cmd.getMovementType(), cmd.getQuantity());
        log.info("Stock movement created: {}", savedStockMovement.getId());

        return inventoryMapper.toDetailDto(savedInventory);
    }
}
