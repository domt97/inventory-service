package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.ReserveStockCmd;
import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.utils.CollectionUtils;
import com.dotran.example.inventory.domain.exception.ValidationException;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.domain.model.StockReservation;
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
public class ReserveStockService implements ReserveStockUseCase {

    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void reserve(ReserveStockCmd reserveStockCmd) {
        log.info("Reserving stock for order: {}", reserveStockCmd.getOrderId().getValue());

        List<SKU> skus = reserveStockCmd.getSkus()
                .stream()
                .map(ReserveStockCmd.ReserveStockSKU::getSku)
                .toList();

        if (CollectionUtils.isEmpty(skus)) {
            log.warn("ReserveStockCmd contains no SKUs to reserve for order: {}", reserveStockCmd.getOrderId().getValue());

            return;
        }

        List<Inventory> inventories = inventoryRepository
                .getByStoreIdAndSKUs(reserveStockCmd.getStoreId(), skus);
        if (CollectionUtils.isEmpty(inventories)) {
            log.warn("No inventories found for SKUs: {} in store: {}", skus, reserveStockCmd.getStoreId());

            throw new ValidationException("Missing inventories for SKUs: " +
                    skus.stream().map(SKU::getValue).collect(Collectors.joining(", ")));
        }

        Map<SKU, Inventory> inventoryMap = inventories.stream()
                .collect(Collectors.toMap(Inventory::getSku, Function.identity()));

        List<StockReservation> reservations = new ArrayList<>();

        for (ReserveStockCmd.ReserveStockSKU reserveStockSKU : reserveStockCmd.getSkus()) {
            Inventory inventory = inventoryMap.get(reserveStockSKU.getSku());

            inventory.reserve(reserveStockSKU.getQuantity());

            StockReservation stockReservation = StockReservation.reserve(
                    reserveStockCmd.getTenantId(),
                    reserveStockCmd.getStoreId(),
                    reserveStockCmd.getOrderId(),
                    reserveStockSKU.getOrderItemId(),
                    inventory.getId(),
                    reserveStockSKU.getQuantity()
            );

            reservations.add(stockReservation);
        }

        List<Inventory> updatedInventories = inventoryRepository.updateBatch(inventories);
        log.info("Updated inventories after reservation: {}", updatedInventories.size());

        List<StockReservation> createdReservations = stockReservationRepository.createBatch(reservations);
        log.info("Created stock reservations: {}", createdReservations.size());

        log.info("Stock reserved successfully for order: {}", reserveStockCmd.getOrderId().getValue());
    }
}
