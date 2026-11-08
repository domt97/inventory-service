package com.dotran.oms.inventory.application.usecase.reservation;

import com.dotran.oms.inventory.application.command.ReleaseStockReservationCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReleaseStockReservationResult;

public interface ReleaseStockReservationUseCase {

    ReleaseStockReservationResult release(ReleaseStockReservationCmd cmd);
}
