package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.mapper.InventoryMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.domain.model.Inventory;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@RequiredArgsConstructor
public class CreateInventoryService implements CreateInventoryUseCase {

    private final InventoryRepository repository;
    private final InventoryMapper mapper;

    @Override
    @Transactional
    public InventoryDetailDto create(CreateInventoryCmd cmd) {
        Inventory inventory = mapper.fromCreatedCmd(cmd);
        inventory.init();

        Inventory savedInventory = repository.save(inventory);

        return mapper.toDetailDto(savedInventory);
    }
}
