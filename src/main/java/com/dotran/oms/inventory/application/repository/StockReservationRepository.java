package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.OrderId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface StockReservationRepository {

    StockReservation create(StockReservation stockReservation);

    List<StockReservation> createBatch(List<StockReservation> stockReservations);

    /**
     * Guarded transition {@code RESERVED -> EXPIRED}, one conditional UPDATE per reservation in ascending id order.
     * Must run inside the caller's transaction.
     *
     * @return ids whose row was still {@code RESERVED} and is now {@code EXPIRED}; any other id was handled concurrently
     */
    Set<StockReservationId> markExpired(Collection<StockReservationId> stockReservationIds);

    List<StockReservation> getByOrderId(OrderId orderId);

    /**
     * Guarded transition {@code RESERVED -> CONFIRMED}, one conditional UPDATE per reservation in ascending id order.
     * Must run inside the caller's transaction.
     *
     * @return ids whose row was still {@code RESERVED} and is now {@code CONFIRMED}; any other id was handled concurrently
     */
    Set<StockReservationId> markConfirmed(Collection<StockReservationId> stockReservationIds);

    /**
     * One page of {@code RESERVED} reservations with {@code expires_at < expiresAtBefore}, ordered by
     * {@code (expires_at, id)} and strictly after {@code after}. Reservations without {@code expires_at} never match.
     */
    List<StockReservation> getExpiredReservations(Instant expiresAtBefore, ExpiryCursor after, int limit);

    Optional<StockReservation> getById(StockReservationId stockReservationId);
}
