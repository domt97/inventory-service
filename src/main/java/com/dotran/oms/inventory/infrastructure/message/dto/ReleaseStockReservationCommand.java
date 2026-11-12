package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * ReleaseStockReservation as sent by order-service (oms-workspace
 * contracts/events/ReleaseStockReservation.schema.json). {@code eventType} has no default, so a missing one is seen
 * by the consumer. {@code restock} is an Object on purpose: a Boolean field would turn {@code 1} or {@code "true"}
 * into true, and this flag decides whether stock comes back; the consumer accepts a JSON boolean only.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReleaseStockReservationCommand {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;

    private UUID merchantId;
    private UUID storeId;
    private UUID orderId;

    private Object restock;
    private String reason;
}
