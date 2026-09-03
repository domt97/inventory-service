package com.dotran.example.inventory.application.usecase.reservation;


import com.dotran.example.inventory.application.command.ReserveStockCmd;

public interface ReserveStockUseCase {

    void reserve(ReserveStockCmd reserveStockCmd);
}
