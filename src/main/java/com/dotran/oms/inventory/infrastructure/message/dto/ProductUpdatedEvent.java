package com.dotran.oms.inventory.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ProductUpdated as published by stores-service (oms-workspace contracts/events/ProductUpdated.schema.json).
 * Statuses stay strings here and are mapped in the consumer, so an unknown value is a contract error, not a crash.
 */
@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductUpdatedEvent {

    private UUID eventId;
    @Builder.Default
    private String eventType = "ProductUpdated";
    private Instant occurredAt;

    private UUID merchantId;
    private UUID storeId;
    private UUID productId;

    private Long version;
    private String status;
    private String name;
    private UUID categoryId;

    private List<Sku> skus;

    @Getter
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Sku {

        private UUID id;
        /** Plain string, unlike ProductCreated's {"value": ...}. */
        private String sku;
        private String name;
        private String status;
        private BigDecimal price;
        private String currency;
    }
}
