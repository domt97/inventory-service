package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;

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

    /**
     * Reservations of one order, scoped by merchant and store, with SKU and product, read with native SQL so the status
     * is current even after native guarded transitions in the same transaction. Ordered by reservation id.
     */
    List<ReservationLineView> getLineViews(MerchantId merchantId, StoreId storeId, OrderId orderId);

    /** Guarded {@code RESERVED -> RELEASED}, ascending id order, inside the caller's transaction; returns the winners. */
    Set<StockReservationId> markReleased(Collection<StockReservationId> stockReservationIds);

    /** Guarded {@code CONFIRMED -> RETURNED}, ascending id order, inside the caller's transaction; returns the winners. */
    Set<StockReservationId> markReturned(Collection<StockReservationId> stockReservationIds);

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
