package com.dotran.oms.inventory.application.usecase.reservation;

import com.dotran.oms.inventory.application.command.ConfirmStockReservationCmd;

public interface ConfirmStockReservationUseCase {

    void confirm(ConfirmStockReservationCmd cmd);
}
