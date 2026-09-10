package com.dotran.example.inventory.application.repository;

import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.StockReservationId;
import com.dotran.example.inventory.domain.model.StockReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository {

    StockReservation create(StockReservation stockReservation);

    List<StockReservation> createBatch(List<StockReservation> stockReservations);

    StockReservation expire(StockReservation stockReservation);

    List<StockReservation> expire(List<StockReservation> stockReservations);

    List<StockReservation> getByOrderId(OrderId orderId);

    List<StockReservation> confirm(List<StockReservation> stockReservations);

    List<StockReservation> getExpiredReservations(Instant expiresAtBefore);

    Optional<StockReservation> getById(StockReservationId stockReservationId);
}
