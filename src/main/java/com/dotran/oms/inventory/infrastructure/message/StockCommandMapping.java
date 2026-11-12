package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared mapping of the stock command DTOs (ADR 0006). A missing field becomes a {@code null} wrapper, never
 * {@code OrderId.of(null)}: the typed ids accept null, and the use cases reject a command by its null fields.
 */
final class StockCommandMapping {

    /** MDC key of the command's order; oms-core {@code MdcKeys} has none. */
    static final String ORDER_ID = "orderId";

    private StockCommandMapping() {
    }

    /**
     * The three command payloads overlap, so a command on another command's topic would be read without an error
     * (a release on the confirm topic would deduct stock). The contract fixes eventType per topic.
     */
    static void requireType(String expected, String actual) {
        if (!expected.equals(actual)) {
            // The actual value is caller-chosen text: not put in the message.
            throw new InvalidStockCommandException("eventType must be " + expected
                    + (actual == null ? ", but is missing" : ", but is another type"));
        }
    }

    static <T> T id(UUID value, Function<UUID, T> of) {
        return value == null ? null : of.apply(value);
    }

    static SKU sku(String value) {
        return value == null ? null : SKU.of(value);
    }

    /** A missing quantity becomes 0, which the use case rejects; a fraction or a value beyond long is refused here. */
    static long quantity(BigDecimal value) {
        if (value == null) {
            return 0;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidStockCommandException("quantity must be a whole number within the long range");
        }
    }

    /** Only a JSON boolean; a missing value stays null and is rejected by the use case. */
    static Boolean restock(Object value) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new InvalidStockCommandException("restock must be a JSON boolean, but is a "
                + (value instanceof Number ? "number" : value instanceof String ? "string" : "structure"));
    }
}
