package com.dotran.oms.inventory.application.usecase.reservation;

import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;
import com.dotran.oms.inventory.application.dto.reservation.ConfirmStockReservationResult;

public interface ConfirmStockReservationUseCase {

    ConfirmStockReservationResult confirm(ConfirmStockReservationCmd cmd);
}
