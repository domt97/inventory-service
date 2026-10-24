package com.dotran.oms.inventory.application.repository;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.model.StockReservation;

import java.time.Instant;

/**
 * Keyset position for paging expired reservations in {@code (expires_at, id)} order.
 * The next page returns only rows strictly after this position.
 */
public record ExpiryCursor(Instant expiresAt, StockReservationId id) {

    /**
     * Position before every reservation. Reservations expire 30 minutes after creation, so none has
     * {@code expires_at} at or before the epoch; such rows would be excluded by design.
     */
    public static ExpiryCursor start() {
        return new ExpiryCursor(Instant.EPOCH, StockReservationId.of(0L));
    }

    public static ExpiryCursor after(StockReservation stockReservation) {
        return new ExpiryCursor(stockReservation.getExpiresAt(), stockReservation.getId());
    }
}
