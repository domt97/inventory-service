package com.dotran.example.inventory.infrastructure.rest.dto.response;

import com.dotran.example.inventory.domain.enums.InventoryStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class InventoryDetailResponse {

    private UUID id;

    private UUID productId;
    private String sku;
    private UUID tenantId;
    private UUID storeId;
    private int quantity;
    private int reservedQuantity;
    private int availableQuantity;

    private InventoryStatus status;

    private Instant createdAt;
    private Instant updatedAt;
}
