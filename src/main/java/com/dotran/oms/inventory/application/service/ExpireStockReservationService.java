package com.dotran.oms.inventory.application.service;

import com.dotran.oms.inventory.application.config.ReservationExpiryProperties;
import com.dotran.oms.inventory.application.repository.ExpiryCursor;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ExpireStockReservationUseCase;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
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
    private final ReservationExpiryProperties reservationExpiryProperties;
    private final Clock clock;

    /**
     * Expires reservations page by page in {@code (expires_at, id)} order until none is left, a page is not full,
     * or the time budget is used up; whatever is left is picked up by the next tick. The cursor only moves forward,
     * so reservations of an inventory that failed (still {@code RESERVED}) do not block the rest of this run.
     */
    @Override
    public void execute() {
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(reservationExpiryProperties.getTimeBudget());
        int batchSize = reservationExpiryProperties.getBatchSize();
        log.info("Executing ExpireStockReservationService at {}", startedAt);

        ExpiryCursor cursor = ExpiryCursor.start();
        int batches = 0;
        long reservationsSeen = 0;
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                log.info("Expiry time budget {} reached after {} batches; remaining expired reservations are left for the next tick",
                        reservationExpiryProperties.getTimeBudget(), batches);
                break;
            }

            List<StockReservation> batch = stockReservationRepository.getExpiredReservations(startedAt, cursor, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            batches++;
            reservationsSeen += batch.size();

            expireByInventory(batch);

            cursor = ExpiryCursor.after(batch.get(batch.size() - 1));
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (reservationsSeen == 0) {
            log.info("No expired stock reservations found at {}", startedAt);
            return;
        }

        Instant finishedAt = clock.instant();
        log.info("Finished executing ExpireStockReservationService at {}, batches: {}, reservations: {}, duration: {}",
                finishedAt, batches, reservationsSeen, Duration.between(startedAt, finishedAt));
    }

    private void expireByInventory(List<StockReservation> expiredReservations) {
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
    }
}
