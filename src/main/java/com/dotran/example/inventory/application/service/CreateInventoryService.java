package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.mapper.InventoryMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.domain.model.Inventory;
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

        List<Inventory> savedInventoryList = repository.createList(inventoryList);

        return savedInventoryList.stream()
                .map(mapper::toDetailDto)
                .toList();
    }
}
