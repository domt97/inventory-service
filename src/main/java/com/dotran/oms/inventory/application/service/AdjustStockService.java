package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.AdjustStockCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.usecase.inventory.AdjustStockUseCase;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.cloud.dynamodb.DynamoDbTenantInfoRepository;
import com.dotran.oms.core.domain.TenantInfo;
import com.dotran.oms.core.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class AdjustStockService implements AdjustStockUseCase {

    private final InventoryRepository repository;
    private final StockMovementRepository stockMovementRepository;
    private final DynamoDbTenantInfoRepository dynamoDbTenantInfoRepository;
    private final InventoryMapper inventoryMapper;

    @Override
    @Transactional
    public InventoryDetailDto adjust(AdjustStockCmd cmd) {
        TenantInfo tenantInfo = dynamoDbTenantInfoRepository.findByTenantId(cmd.getTenantId())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));

        Inventory inventory = repository.getById(cmd.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        inventory.validateTenantAndStore(tenantInfo.getId(), cmd.getStoreId());

        inventory.adjust(cmd.getMovementType(), cmd.getQuantity());

        StockMovement newStockMovement = StockMovement.newInstance(
                inventory,
                cmd.getMovementType(),
                cmd.getQuantity(),
                cmd.getReason()
        );

        Inventory savedInventory = repository.update(inventory);
        StockMovement savedStockMovement = stockMovementRepository.create(newStockMovement);

        log.info("Inventory updated: id = {}, type: {}, quantity: {}",
                savedInventory.getId().getValue(), cmd.getMovementType(), cmd.getQuantity());
        log.info("Stock movement created: id = {}", savedStockMovement.getId().getValue());

        return inventoryMapper.toDetailDto(savedInventory);
    }
}
