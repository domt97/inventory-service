package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.usecase.inventory.LoadInventoryUseCase;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.exception.NotFoundException;
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
