package com.dotran.example.inventory.common.domain.valueobject;

public class StockMovementId extends BaseId<Long> {

    public StockMovementId(Long value) {
        super(value);
    }

    public static StockMovementId of(Long value) {
        return new StockMovementId(value);
    }
}
