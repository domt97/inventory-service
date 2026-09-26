package com.dotran.oms.inventory.infrastructure.rest.dto.request;

import com.dotran.oms.inventory.domain.enums.StockMovementType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdjustStockRequest {

    @NotNull
    private UUID tenantId;

    @NotNull
    private UUID storeId;

    @Positive
    private Long quantity;

    @NotNull
    private StockMovementType movementType;
    private String reason;
}
