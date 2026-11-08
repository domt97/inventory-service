package com.dotran.oms.inventory.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Types of stock movement (BR-INV-05). Each type has one {@link Effect}; the quantity of a movement is always positive
 * and its direction follows from the type.
 */
public enum StockMovementType {

    RECEIVE(Effect.ON_HAND_IN),
    /** Confirmed sale: deducts on-hand stock and the hold (order PLACED, BR-INV-04). Manual adjustment allowed too. */
    SALE(Effect.ON_HAND_OUT),
    /** Goods put back into stock: manual, or a cancelled order with restock (BR-ORD-09). */
    RETURN(Effect.ON_HAND_IN),
    ADJUSTMENT_IN(Effect.ON_HAND_IN),
    ADJUSTMENT_OUT(Effect.ON_HAND_OUT),
    DAMAGE(Effect.ON_HAND_OUT),
    TRANSFER_IN(Effect.ON_HAND_IN),
    TRANSFER_OUT(Effect.ON_HAND_OUT),
    /** Order hold placed (reserved quantity up). Never a manual adjustment. */
    RESERVE(Effect.HOLD),
    /** Order hold released by a release command. Never a manual adjustment. */
    RESERVE_RELEASE(Effect.HOLD_RELEASE),
    /** Order hold freed by expiry. Never a manual adjustment. */
    RESERVE_EXPIRE(Effect.HOLD_RELEASE);

    /** What a movement changes. */
    public enum Effect {
        /** On-hand quantity up. */
        ON_HAND_IN,
        /** On-hand quantity down. */
        ON_HAND_OUT,
        /** Reserved quantity up, on-hand unchanged. */
        HOLD,
        /** Reserved quantity down, on-hand unchanged. */
        HOLD_RELEASE
    }

    private final Effect effect;

    StockMovementType(Effect effect) {
        this.effect = effect;
    }

    public Effect effect() {
        return effect;
    }

    public static final List<StockMovementType> STOCK_IN_TYPES = Arrays.stream(values())
            .filter(StockMovementType::isStockIn).toList();

    public static final List<StockMovementType> STOCK_OUT_TYPES = Arrays.stream(values())
            .filter(StockMovementType::isStockOut).toList();

    public boolean isStockIn() {
        return effect == Effect.ON_HAND_IN;
    }

    public boolean isStockOut() {
        return effect == Effect.ON_HAND_OUT;
    }

    /** Only on-hand changes can be adjusted by hand; the hold types belong to orders. */
    public boolean isManualAdjustable() {
        return isStockIn() || isStockOut();
    }
}
