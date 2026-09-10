package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.repository.InventoryRepository;
import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.exception.NotFoundException;
import com.dotran.example.inventory.common.utils.CollectionUtils;
import com.dotran.example.inventory.domain.model.Inventory;
import com.dotran.example.inventory.domain.model.StockReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireByInventoryTransaction {

    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(InventoryId inventoryId, List<StockReservation> expiredReservations) {
        log.info("Executing ExpireByInventoryTransaction for {} reservations", expiredReservations.size());

        if (CollectionUtils.isEmpty(expiredReservations)) {
            log.info("No reservations to expire");
            return;
        }

        for (StockReservation stockReservation : expiredReservations) {
            stockReservation.expire();
        }
        List<StockReservation> updatedReservations = stockReservationRepository.expire(expiredReservations);

        long releaseQuantity = updatedReservations.stream()
                .mapToLong(StockReservation::getQuantity)
                .sum();

        Inventory inventory = inventoryRepository.getById(inventoryId)
                .orElseThrow(() -> new NotFoundException("Inventory not found: " + inventoryId));

        inventory.release(releaseQuantity);

        inventoryRepository.update(inventory);


        log.info("Expired {} reservations and released {} quantity for inventory {}",
                updatedReservations.size(),
                releaseQuantity,
                inventoryId);
    }
}
