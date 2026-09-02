package com.dotran.example.inventory.application.dto;

import com.dotran.example.inventory.domain.enums.StockMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementDto {

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
