package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class ConfirmStockReservationService implements ConfirmStockReservationUseCase {

    private final StockReservationRepository stockReservationRepository;
    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void confirm(ConfirmStockReservationCmd cmd) {
        log.info("Confirming stock reservation for order: {}", cmd.getOrderId().getValue());

        List<StockReservation> reservations = stockReservationRepository.getByOrderId(cmd.getOrderId());
        if (CollectionUtils.isEmpty(reservations)) {
            log.warn("No stock reservations found for order: {}", cmd.getOrderId().getValue());
            throw new ValidationException("No stock reservations found for order: " + cmd.getOrderId().getValue());
        }

        if (reservations.stream().allMatch(r -> r.getStatus() == ReservationStatus.CONFIRMED)) {
            log.info("Stock reservations already confirmed for order: {}", cmd.getOrderId().getValue());
            return;
        }

        for (StockReservation stockReservation : reservations) {
            if (stockReservation.getStatus() != ReservationStatus.RESERVED) {
                throw new InvalidReservationStateException(
                        "Reservation " + stockReservation.getId().getValue() + " is not RESERVED: " + stockReservation.getStatus());
            }
            stockReservation.confirm();
        }

        // Guarded RESERVED -> CONFIRMED: if any row was changed concurrently (e.g. expired), roll back everything.
        Set<StockReservationId> confirmedIds = stockReservationRepository.markConfirmed(
                reservations.stream().map(StockReservation::getId).toList());
        if (confirmedIds.size() < reservations.size()) {
            throw new InvalidReservationStateException(
                    "Stock reservations of order " + cmd.getOrderId().getValue() + " were modified concurrently");
        }

        // Sorted so concurrent transactions lock inventory rows in the same order.
        Map<InventoryId, Long> quantityByInventory = reservations.stream()
                .collect(Collectors.groupingBy(
                        StockReservation::getInventoryId,
                        () -> new TreeMap<>(Comparator.comparing(InventoryId::getValue)),
                        Collectors.summingLong(StockReservation::getQuantity)
                ));

        for (Map.Entry<InventoryId, Long> entry : quantityByInventory.entrySet()) {
            int updated = inventoryRepository.confirm(entry.getKey(), entry.getValue());
            if (updated == 0) {
                log.error("Failed to confirm inventory for inventoryId: {}", entry.getKey());
                throw new InvalidReservationException();
            }
        }

        log.info("Stock reservation confirmed successfully for order: {}, total: {}",
                cmd.getOrderId().getValue(), reservations.size());
    }
}
