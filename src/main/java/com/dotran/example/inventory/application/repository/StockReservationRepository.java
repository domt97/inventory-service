package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.domain.model.StockReservation;

public interface StockReservationRepository {

    StockReservation create(StockReservation stockReservation);
}
