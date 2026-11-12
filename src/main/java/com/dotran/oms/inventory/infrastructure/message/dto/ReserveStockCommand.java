package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ReserveStock as sent by order-service (oms-workspace contracts/events/ReserveStock.schema.json). {@code eventType}
 * has no default, so a missing one is seen by the consumer. {@code quantity} is a BigDecimal on purpose: an integer
 * field would silently truncate {@code 2.7} to 2; the consumer rejects a fraction instead.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReserveStockCommand {

    private UUID eventId;
    private String eventType;
    private Instant occurredAt;

    private UUID merchantId;
    private UUID storeId;
    private UUID orderId;

    private List<Line> lines;

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Line {

        private UUID orderItemId;
        private UUID productId;
        private String sku;
        private BigDecimal quantity;
    }
}
