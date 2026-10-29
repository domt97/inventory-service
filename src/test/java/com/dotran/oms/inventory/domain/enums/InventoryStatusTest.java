package com.dotran.oms.inventory.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryStatus.orderable (BR-INV-08)")
class InventoryStatusTest {

    @ParameterizedTest(name = "product {0}, SKU {1}, present {2} → {3}")
    @CsvSource({
            "ACTIVE,   ACTIVE,   true,  ACTIVE",
            "ACTIVE,   ACTIVE,   false, INACTIVE",
            "ACTIVE,   INACTIVE, true,  INACTIVE",
            "INACTIVE, ACTIVE,   true,  INACTIVE",
            "DRAFT,    ACTIVE,   true,  INACTIVE",
            "INACTIVE, INACTIVE, false, INACTIVE"
    })
    void truthTable(ProductStatus product, ProductSkuStatus sku, boolean present, InventoryStatus expected) {
        assertThat(InventoryStatus.orderable(product, sku, present)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a SKU missing from the snapshot (null status) is INACTIVE")
    void removedSku_isInactive() {
        assertThat(InventoryStatus.orderable(ProductStatus.ACTIVE, null, false)).isEqualTo(InventoryStatus.INACTIVE);
    }
}
