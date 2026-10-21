package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class ExpireStockReservationService implements ExpireStockReservationUseCase {

    private final StockReservationRepository stockReservationRepository;
    private final ExpireByInventoryTransaction expireByInventoryTransaction;

    @Override
    public void execute() {
        Instant startedAt = Instant.now();
        log.info("Executing ExpireStockReservationService at {}", startedAt);

        List<StockReservation> expiredReservations = stockReservationRepository.getExpiredReservations(startedAt);
        if (expiredReservations.isEmpty()) {
            log.info("No expired stock reservations found at {}", startedAt);
            return;
        }

        Map<InventoryId, List<StockReservation>> expiredByInventory =
                expiredReservations.stream()
                        .collect(Collectors.groupingBy(
                                StockReservation::getInventoryId
                        ));

        for (Map.Entry<InventoryId, List<StockReservation>> entry : expiredByInventory.entrySet()) {
            InventoryId inventoryId = entry.getKey();
            List<StockReservation> reservations = entry.getValue();
            try {
                expireByInventoryTransaction.execute(inventoryId, reservations);
            } catch (RuntimeException e) {
                // That inventory's transaction rolled back; its reservations stay RESERVED and are retried next run.
                log.error("Reservation expiry failed for inventory {}", inventoryId, e);
            }
        }

        Instant finishedAt = Instant.now();
        log.info("Finished executing ExpireStockReservationService at {}, duration: {}",
                finishedAt, Duration.between(startedAt, finishedAt));
    }
}
