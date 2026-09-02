package com.dotran.example.inventory.domain.enums;

import java.util.List;

public enum StockMovementType {

    RECEIVE,
    SALE,
    RETURN,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT,
    DAMAGE,
    TRANSFER_IN,
    TRANSFER_OUT;

    public static final List<StockMovementType> STOCK_IN_TYPES = List.of(
            RECEIVE,
            RETURN,
            ADJUSTMENT_IN,
            TRANSFER_IN
    );

    public static final List<StockMovementType> STOCK_OUT_TYPES = List.of(
            SALE,
            ADJUSTMENT_OUT,
            DAMAGE,
            TRANSFER_OUT
    );

    public boolean isStockIn() {
        return STOCK_IN_TYPES.contains(this);
    }

    public boolean isStockOut() {
        return STOCK_OUT_TYPES.contains(this);
    }
}
