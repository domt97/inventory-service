package com.dotran.oms.inventory.common.domain.valueobject;

import com.dotran.oms.core.domain.id.BaseId;

public class StockReservationId extends BaseId<Long> {

    public StockReservationId(Long value) {
        super(value);
    }

    public static StockReservationId of(Long value) {
        return new StockReservationId(value);
    }

}
