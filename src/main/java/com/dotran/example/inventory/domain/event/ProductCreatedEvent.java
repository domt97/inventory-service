package com.dotran.example.inventory.domain.event;

import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCreatedEvent {

    private UUID eventId;
    private String eventType = "ProductCreated";
    private Instant occurredAt;

    private UUID tenantId;
    private UUID storeId;
    private UUID productId;

    private List<SKU> skus;
}
