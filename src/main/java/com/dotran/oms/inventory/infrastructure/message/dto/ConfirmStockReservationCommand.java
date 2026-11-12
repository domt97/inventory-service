package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * ConfirmStockReservation as sent by order-service (oms-workspace
 * contracts/events/ConfirmStockReservation.schema.json). {@code eventType} has no default, so a missing one is seen
 * by the consumer.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConfirmStockReservationCommand {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;

    private UUID merchantId;
    private UUID storeId;
    private UUID orderId;
}
