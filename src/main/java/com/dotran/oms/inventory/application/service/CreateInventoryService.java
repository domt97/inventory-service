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
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class CreateInventoryService implements CreateInventoryUseCase {

    private final InventoryRepository repository;
    private final InventoryMapper mapper;

    @Override
    @Transactional
    public List<InventoryDetailDto> create(CreateInventoryCmd cmd) {
        if (cmd.getSkus().isEmpty()) {
            return List.of();
        }

        List<Inventory> existing = repository.getByStoreIdAndSKUs(cmd.getStoreId(), cmd.getSkus());
        Map<SKU, Inventory> existingBySku = existing.stream()
                .collect(Collectors.toMap(Inventory::getSku, Function.identity()));

        for (Inventory inv : existing) {
            if (!inv.getProductId().equals(cmd.getProductId())) {
                log.warn("Inventory exists for store={} sku={} with different productId={} (incoming productId={}); skipping",
                        cmd.getStoreId(), inv.getSku(), inv.getProductId(), cmd.getProductId());
            } else {
                log.info("Skipping already-existing inventory for store={} sku={}", cmd.getStoreId(), inv.getSku());
            }
        }

        List<SKU> newSkus = cmd.getSkus().stream()
                .filter(sku -> !existingBySku.containsKey(sku))
                .toList();

        List<Inventory> created = List.of();
        if (!newSkus.isEmpty()) {
            List<Inventory> toCreate = new ArrayList<>();
            for (SKU sku : newSkus) {
                Inventory inventory = Inventory.builder()
                        .tenantId(cmd.getTenantId())
                        .storeId(cmd.getStoreId())
                        .productId(cmd.getProductId())
                        .sku(sku)
                        .build();
                inventory.init();
                toCreate.add(inventory);
            }
            created = repository.createBatch(toCreate);
        }

        List<Inventory> result = new ArrayList<>(existing);
        result.addAll(created);
        return result.stream().map(mapper::toDetailDto).toList();
    }
}
