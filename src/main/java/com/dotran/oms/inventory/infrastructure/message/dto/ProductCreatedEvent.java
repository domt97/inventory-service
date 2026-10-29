package com.dotran.oms.inventory.infrastructure.message.dto;

import com.dotran.oms.core.domain.id.SKU;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCreatedEvent {

    private UUID eventId;
    @Builder.Default
    private String eventType = "ProductCreated";
    private Instant occurredAt;

    private UUID merchantId;
    private UUID storeId;
    private UUID productId;

    /** Optional, always 0; a missing value means 0 (oms-workspace contracts/events/ProductCreated.schema.json). */
    private Long version;

    private List<SKU> skus;
}
