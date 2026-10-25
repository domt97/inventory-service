package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.command.AdjustStockCmd;
import com.dotran.oms.inventory.application.dto.InventoryDetailDto;
import com.dotran.oms.inventory.application.mapper.InventoryMapper;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.MerchantRepository;
import com.dotran.oms.inventory.application.usecase.inventory.AdjustStockUseCase;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.MerchantInfo;
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
    private final MerchantRepository merchantRepository;
    private final InventoryMapper inventoryMapper;

    @Override
    @Transactional
    public InventoryDetailDto adjust(AdjustStockCmd cmd) {
        merchantRepository.findByMerchantId(cmd.getMerchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found"));

        Inventory inventory = repository.getById(cmd.getInventoryId())
                .orElseThrow(() -> new NotFoundException("Inventory not found"));

        inventory.validateMerchantAndStore(cmd.getMerchantId(), cmd.getStoreId());
        inventory.adjust(cmd.getMovementType(), cmd.getQuantity()); // domain validation

        int updated = cmd.getMovementType().isStockIn()
                ? repository.stockIn(cmd.getInventoryId(), cmd.getMerchantId(), cmd.getStoreId(), cmd.getQuantity())
                : repository.stockOut(cmd.getInventoryId(), cmd.getMerchantId(), cmd.getStoreId(), cmd.getQuantity());

        if (updated == 0) {
            throw new InsufficientStockException();
        }

        StockMovement savedStockMovement = stockMovementRepository.create(StockMovement.newInstance(
                inventory,
                cmd.getMovementType(),
                cmd.getQuantity(),
                cmd.getReferenceType(),
                cmd.getReferenceId(),
                cmd.getReason()
        ));

        log.info("Inventory adjusted: id = {}, type: {}, quantity: {}",
                inventory.getId().getValue(), cmd.getMovementType(), cmd.getQuantity());
        log.info("Stock movement created: id = {}", savedStockMovement.getId().getValue());

        return inventoryMapper.toDetailDto(inventory);
    }
}
