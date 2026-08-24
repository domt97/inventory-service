package com.dotran.example.inventory.infrastructure.rest.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInventoryRequest {

    @NotNull
    private UUID tenantId;

    @NotNull
    private UUID storeId;

    @NotNull
    private UUID productId;

    @NotNull
    private String sku;
}
