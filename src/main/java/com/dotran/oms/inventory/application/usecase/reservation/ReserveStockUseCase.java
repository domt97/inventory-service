package com.dotran.oms.inventory.application.usecase.reservation;

import com.dotran.oms.inventory.application.command.ReserveStockCmd;
import com.dotran.oms.inventory.application.dto.reservation.ReserveStockResult;

public interface ReserveStockUseCase {

    ReserveStockResult reserve(ReserveStockCmd cmd);
}
