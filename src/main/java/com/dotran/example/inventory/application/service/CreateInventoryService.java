package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.CreateInventoryCommand;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.usecase.inventory.CreateInventoryUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@UseCase
@RequiredArgsConstructor
public class CreateInventoryService implements CreateInventoryUseCase {

    @Override
    @Transactional
    public InventoryDetailDto create(CreateInventoryCommand command) {
        return null;
    }
}
