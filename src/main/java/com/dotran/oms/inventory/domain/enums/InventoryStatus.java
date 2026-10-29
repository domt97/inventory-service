package com.dotran.oms.inventory.domain.enums;

public enum InventoryStatus {
    ACTIVE,
    INACTIVE;

    /**
     * BR-INV-08: an inventory is orderable (ACTIVE) only when its product is ACTIVE, its SKU is ACTIVE
     * and the SKU is still part of the product; otherwise it is INACTIVE.
     */
    public static InventoryStatus orderable(ProductStatus productStatus, ProductSkuStatus skuStatus, boolean presentInProduct) {
        return presentInProduct
                && productStatus == ProductStatus.ACTIVE
                && skuStatus == ProductSkuStatus.ACTIVE
                ? ACTIVE
                : INACTIVE;
    }
}
