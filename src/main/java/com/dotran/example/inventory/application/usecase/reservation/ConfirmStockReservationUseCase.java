package com.dotran.example.inventory.application.usecase.reservation;

import com.dotran.example.inventory.application.command.ConfirmStockReservationCmd;

public interface ConfirmStockReservationUseCase {

    void confirm(ConfirmStockReservationCmd cmd);
}
