package com.dotran.example.inventory.application.usecase.inventory;

import com.dotran.example.inventory.application.command.CreateInventoryCmd;
import com.dotran.example.inventory.application.dto.InventoryDetailDto;

import java.util.List;

public interface CreateInventoryUseCase {

    List<InventoryDetailDto> create(CreateInventoryCmd command);
}
