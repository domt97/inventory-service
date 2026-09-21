package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.CreateInventoryCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.SKU;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@UseCase
@RequiredArgsConstructor
public class CreateInventoryService implements CreateInventoryUseCase {

    private final InventoryRepository repository;
    private final InventoryMapper mapper;

    @Override
    @Transactional
    public List<InventoryDetailDto> create(CreateInventoryCmd cmd) {
        List<Inventory> inventoryList = new ArrayList<>();
        for (SKU sku : cmd.getSkus()) {
            Inventory inventory = Inventory.builder()
                    .tenantId(cmd.getTenantId())
                    .storeId(cmd.getStoreId())
                    .productId(cmd.getProductId())
                    .sku(sku)
                    .build();
            inventory.init();

            inventoryList.add(inventory);
        }

        List<Inventory> savedInventoryList = repository.createBatch(inventoryList);

        return savedInventoryList.stream()
                .map(mapper::toDetailDto)
                .toList();
    }
}
