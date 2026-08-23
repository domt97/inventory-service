package com.dotran.example.inventory.common.domain.valueobject;

public class StockReservationId extends BaseId<Long> {

    public StockReservationId(Long value) {
        super(value);
    }

    public static StockReservationId of(Long value) {
        return new StockReservationId(value);
    }

}
