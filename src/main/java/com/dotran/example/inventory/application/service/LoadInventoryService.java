package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.dto.InventoryDetailDto;
import com.dotran.example.inventory.application.mapper.InventoryMapper;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.usecase.inventory.LoadInventoryUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.common.utils.CollectionUtils;
import com.dotran.example.inventory.domain.model.Inventory;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@UseCase
@RequiredArgsConstructor
public class LoadInventoryService implements LoadInventoryUseCase {

    private final InventoryRepository repository;
    private final InventoryMapper mapper;

    @Override
    @Transactional
    public InventoryDetailDto loadById(InventoryId id) {
        Inventory inventory = repository.getById(id)
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        return mapper.toDetailDto(inventory);
    }

    @Override
    @Transactional
    public List<InventoryDetailDto> loadByProductId(ProductId productId) {
        List<Inventory> inventories = repository.getByProductId(productId);

        if (CollectionUtils.isEmpty(inventories)) {
            throw new NotFoundException("No inventories found for productId: " + productId.getValue());
        }

        return inventories.stream()
                .map(mapper::toDetailDto)
                .toList();
    }
}
