package com.dotran.oms.inventory.application.event;

import java.util.UUID;

/**
 * One row for {@code outbox_events} (ADR 0001/0006): {@code eventId} is also the row id (Kafka header {@code id}),
 * {@code aggregateId} the Kafka key (orderId), {@code eventType} the topic.
 */
public record OutboxMessage(UUID eventId,
                            String aggregateType,
                            UUID aggregateId,
                            String eventType,
                            UUID merchantId,
                            StockReply payload) {

    public static final String AGGREGATE_TYPE = "StockReservation";

    public static OutboxMessage of(StockReply reply) {
        return new OutboxMessage(reply.eventId(), AGGREGATE_TYPE, reply.orderId(), reply.eventType(),
                reply.merchantId(), reply);
    }
}
