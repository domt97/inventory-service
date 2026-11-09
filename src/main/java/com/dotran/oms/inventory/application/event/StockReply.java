package com.dotran.oms.inventory.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of the stock replies (ADR 0006, {@code oms-workspace/architecture/contracts/events/StockReservation*.schema.json},
 * {@code StockReserved.schema.json}). Plain values only (UUID, String, numbers, enum names): typed ids would serialise as
 * objects. Field names are the JSON property names; every field is written, nulls included.
 */
public sealed interface StockReply {

    UUID eventId();

    String eventType();

    Instant occurredAt();

    UUID merchantId();

    UUID storeId();

    UUID orderId();

    /** eventId of the command answered; null only for {@link Expired}. */
    UUID causationId();

    record Reserved(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId, UUID orderId,
                    UUID causationId, Instant expiresAt, List<ReservedLine> lines) implements StockReply {
        public static final String TYPE = "StockReserved";
    }

    record ReservedLine(UUID orderItemId, String sku, long quantity, Instant expiresAt) {
    }

    record Rejected(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId, UUID orderId,
                    UUID causationId, String orderReason, List<RejectedLine> lines) implements StockReply {
        public static final String TYPE = "StockReservationRejected";
    }

    record RejectedLine(UUID orderItemId, String sku, String reason) {
    }

    record Confirmed(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId, UUID orderId,
                     UUID causationId, List<StatusLine> lines) implements StockReply {
        public static final String TYPE = "StockReservationConfirmed";
    }

    record ConfirmationFailed(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId,
                              UUID orderId, UUID causationId, String reason, List<StatusLine> lines)
            implements StockReply {
        public static final String TYPE = "StockReservationConfirmationFailed";
    }

    record StatusLine(UUID orderItemId, String status) {
    }

    record Released(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId, UUID orderId,
                    UUID causationId, boolean restock, List<ReleasedLine> lines) implements StockReply {
        public static final String TYPE = "StockReservationReleased";
    }

    record ReleasedLine(UUID orderItemId, String status, boolean restocked) {
    }

    /** Emitted by the expiry job, not a reply: {@code causationId} is always null. */
    record Expired(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId, UUID orderId,
                   UUID causationId, List<ExpiredLine> lines) implements StockReply {
        public static final String TYPE = "StockReservationExpired";
    }

    record ExpiredLine(UUID orderItemId, String sku) {
    }
}
