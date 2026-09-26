package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.OrderId;

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
