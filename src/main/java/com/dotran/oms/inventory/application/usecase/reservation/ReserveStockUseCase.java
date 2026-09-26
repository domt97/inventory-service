package com.dotran.oms.inventory.application.usecase.reservation;


import com.dotran.oms.inventory.application.command.ReserveStockCmd;

public interface ReserveStockUseCase {

    void reserve(ReserveStockCmd reserveStockCmd);
}
