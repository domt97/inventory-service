package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.domain.model.StockReservation;

import java.util.List;

public interface StockReservationRepository {

    StockReservation create(StockReservation stockReservation);

    List<StockReservation> getByOrderId(OrderId orderId);

    List<StockReservation> confirm(List<StockReservation> stockReservations);
}
