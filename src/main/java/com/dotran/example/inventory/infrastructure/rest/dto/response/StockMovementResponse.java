package com.dotran.example.inventory.infrastructure.rest.dto.response;

import com.dotran.example.inventory.domain.enums.StockMovementType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class StockMovementResponse {

    private Long id;
    private UUID tenantId;
    private UUID storeId;

    private UUID inventoryId;

    private StockMovementType type;

    private Long quantity;

    private String referenceType;
    private UUID referenceId;

    private String reason;

    private Instant createdAt;
}
