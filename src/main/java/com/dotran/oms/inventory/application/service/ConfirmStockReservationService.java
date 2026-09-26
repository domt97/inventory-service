package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.core.util.CollectionUtils;
import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.annotation.UseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@UseCase
@RequiredArgsConstructor
@Slf4j
public class ConfirmStockReservationService implements ConfirmStockReservationUseCase {

    private final StockReservationRepository stockReservationRepository;

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

        List<StockReservation> updatedReservations = stockReservationRepository.confirm(reservations);

        log.info("Stock reservation confirmed successfully for order: {}, total: {}",
                cmd.getOrderId().getValue(), updatedReservations.size());
    }
}
