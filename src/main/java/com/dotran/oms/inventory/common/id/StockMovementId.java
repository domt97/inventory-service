package com.dotran.oms.inventory.common.id;

import com.dotran.oms.core.domain.id.BaseId;

public class StockMovementId extends BaseId<Long> {

    public StockMovementId(Long value) {
        super(value);
    }

    public static StockMovementId of(Long value) {
        return new StockMovementId(value);
    }
}
