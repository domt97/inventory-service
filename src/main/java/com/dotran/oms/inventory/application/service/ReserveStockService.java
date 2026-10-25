package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ReserveStockUseCase;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.SKU;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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

        List<SKU> missingSkus = skus.stream()
                .filter(sku -> !inventoryMap.containsKey(sku))
                .toList();
        if (!missingSkus.isEmpty()) {
            throw new ValidationException("Missing inventories for SKUs: " +
                    missingSkus.stream().map(SKU::getValue).collect(Collectors.joining(", ")));
        }

        List<StockReservation> reservations = new ArrayList<>();

        // Sorted by inventory id so concurrent transactions lock inventory rows in the same order.
        List<ReserveStockCmd.ReserveStockSKU> sortedSkus = reserveStockCmd.getSkus().stream()
                .sorted(Comparator.comparing(line -> inventoryMap.get(line.getSku()).getId().getValue()))
                .toList();

        for (ReserveStockCmd.ReserveStockSKU reserveStockSKU : sortedSkus) {
            Inventory inventory = inventoryMap.get(reserveStockSKU.getSku());

            inventory.reserve(reserveStockSKU.getQuantity());

            int updated = inventoryRepository.reserve(inventory.getId(), reserveStockSKU.getQuantity());
            if (updated == 0) {
                log.warn("Insufficient stock for SKU: {} in store: {}", reserveStockSKU.getSku(), reserveStockCmd.getStoreId());
                throw new InsufficientStockException();
            }

            StockReservation stockReservation = StockReservation.reserve(
                    reserveStockCmd.getMerchantId(),
                    reserveStockCmd.getStoreId(),
                    reserveStockCmd.getOrderId(),
                    reserveStockSKU.getOrderItemId(),
                    inventory.getId(),
                    reserveStockSKU.getQuantity()
            );

            reservations.add(stockReservation);
        }

        List<StockReservation> createdReservations = stockReservationRepository.createBatch(reservations);
        log.info("Stock reserved successfully for order: {}, total reservations: {}",
                reserveStockCmd.getOrderId().getValue(), createdReservations.size());
    }
}
