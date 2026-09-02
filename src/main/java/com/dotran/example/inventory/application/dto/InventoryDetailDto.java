package com.dotran.example.inventory.application.dto;

import com.dotran.example.inventory.domain.enums.InventoryStatus;
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
public class InventoryDetailDto {

    private UUID id;

    private UUID productId;
    private String sku;
    private UUID tenantId;
    private UUID storeId;
    private Long quantity;
    private Long reservedQuantity;
    private Long availableQuantity;

    private InventoryStatus status;

    private Instant createdAt;
    private Instant updatedAt;
}
