package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.event.StockReplyFactory;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.OutboxEventRepository;
import com.dotran.oms.inventory.application.repository.StockMovementRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireByInventoryTransaction {

    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StockReplyFactory replyFactory;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(InventoryId inventoryId, List<StockReservation> expiredReservations) {
        if (CollectionUtils.isEmpty(expiredReservations)) {
            log.info("No reservations to expire");
            return;
        }

        log.info("Executing ExpireByInventoryTransaction for {} reservations", expiredReservations.size());

        for (StockReservation stockReservation : expiredReservations) {
            stockReservation.expire();
        }

        // Guarded RESERVED -> EXPIRED: only reservations that are still RESERVED in the DB release stock.
        Set<StockReservationId> expiredIds = stockReservationRepository.markExpired(
                expiredReservations.stream().map(StockReservation::getId).toList());

        List<StockReservation> updatedReservations = expiredReservations.stream()
                .filter(r -> expiredIds.contains(r.getId()))
                .toList();

        if (updatedReservations.size() < expiredReservations.size()) {
            log.info("Skipped {} reservations for inventory {}: already confirmed, released or expired concurrently",
                    expiredReservations.size() - updatedReservations.size(), inventoryId);
        }
        if (updatedReservations.isEmpty()) {
            return;
        }

        long releaseQuantity = updatedReservations.stream()
                .mapToLong(StockReservation::getQuantity)
                .sum();

        Inventory inventory = inventoryRepository.getById(inventoryId)
                .orElseThrow(() -> new NotFoundException("Inventory not found: " + inventoryId));
        inventory.release(releaseQuantity);

        int updated = inventoryRepository.release(inventoryId, releaseQuantity);
        if (updated == 0) {
            log.error("Failed to release inventory for inventoryId: {}", inventoryId);
            throw new InvalidReservationException();
        }

        // One RESERVE_EXPIRE movement per reservation this transaction expired (BR-INV-05). A count mismatch is only
        // logged: throwing would roll back and retry this inventory on every tick.
        List<StockReservationId> winnerIds = updatedReservations.stream().map(StockReservation::getId).toList();
        int movements = stockMovementRepository.insertForReservations(winnerIds, StockMovementType.RESERVE_EXPIRE,
                ReservationStatus.EXPIRED, null);
        if (movements < winnerIds.size()) {
            log.warn("Wrote {} RESERVE_EXPIRE movements for {} expired reservations of inventory {}",
                    movements, winnerIds.size(), inventoryId);
        }

        // StockReservationExpired, one per (order, merchant, store) of the reservations this transaction expired.
        // The mapping never throws; a database failure here rolls this inventory back and the next tick retries it.
        outboxEventRepository.saveAll(replyFactory.expired(updatedReservations, inventory.getSku()));

        log.info("Expired {} reservations and released {} quantity for inventory {}",
                updatedReservations.size(), releaseQuantity, inventoryId);
    }
}
