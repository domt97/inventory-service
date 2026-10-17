package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.core.domain.id.InventoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

        for (StockReservation stockReservation : reservations) {
            stockReservation.confirm();
        }

        stockReservationRepository.confirm(reservations);

        Map<InventoryId, Long> quantityByInventory = reservations.stream()
                .collect(Collectors.groupingBy(
                        StockReservation::getInventoryId,
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
