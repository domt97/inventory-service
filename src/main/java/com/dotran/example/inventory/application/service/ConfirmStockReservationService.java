package com.dotran.example.inventory.application.service;

import com.dotran.example.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.application.usecase.reservation.ConfirmStockReservationUseCase;
import com.dotran.example.inventory.common.annotation.UseCase;
import com.dotran.example.inventory.common.utils.CollectionUtils;
import com.dotran.example.inventory.domain.exception.ValidationException;
import com.dotran.example.inventory.domain.model.StockReservation;
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
