package com.dotran.example.inventory.application.dto;

import com.dotran.example.inventory.domain.enums.InventoryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryDetailDto {

    private UUID id;

    private UUID productId;
    private UUID tenantId;
    private UUID storeId;
    private int quantity;
    private int reservedQuantity;
    private int availableQuantity;

    private InventoryStatus status;

    private String createdAt;
    private String updatedAt;
}
