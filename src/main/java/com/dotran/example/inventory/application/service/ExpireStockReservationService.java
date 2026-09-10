package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.domain.model.StockReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn(
                        "Inventory was modified concurrently. " +
                                "Reservations will be retried by the next execution.",
                        e
                );
            }
        }

        Instant finishedAt = Instant.now();
        log.info("Finished executing ExpireStockReservationService at {}, duration: {}",
                finishedAt, Duration.between(startedAt, finishedAt));
    }
}
