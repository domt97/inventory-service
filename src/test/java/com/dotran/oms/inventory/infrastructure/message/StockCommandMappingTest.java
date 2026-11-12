package com.dotran.oms.inventory.infrastructure.message;

import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.inventory.domain.exception.InvalidStockCommandException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockCommandMapping")
class StockCommandMappingTest {

    @Test
    @DisplayName("a missing id or SKU becomes a null wrapper, not a wrapper around null")
    void nullStaysNull() {
        UUID id = UUID.randomUUID();

        assertThat(StockCommandMapping.id(null, OrderId::of)).isNull();
        assertThat(StockCommandMapping.id(id, OrderId::of)).isEqualTo(OrderId.of(id));
        assertThat(StockCommandMapping.sku(null)).isNull();
        assertThat(StockCommandMapping.sku("A")).isEqualTo(SKU.of("A"));
    }

    @Test
    @DisplayName("eventType must be the topic's message type; the message never repeats the received value")
    void requireType() {
        assertThatCode(() -> StockCommandMapping.requireType("ReserveStock", "ReserveStock")).doesNotThrowAnyException();
        assertThatThrownBy(() -> StockCommandMapping.requireType("ReserveStock", null))
                .isInstanceOf(InvalidStockCommandException.class).hasMessageContaining("missing");
        assertThatThrownBy(() -> StockCommandMapping.requireType("ConfirmStockReservation", "ReleaseStockReservation\nx"))
                .isInstanceOf(InvalidStockCommandException.class)
                .hasMessageContaining("ConfirmStockReservation")
                .hasMessageNotContaining("Release");
        assertThatThrownBy(() -> StockCommandMapping.requireType("ReserveStock", "reservestock"))
                .isInstanceOf(InvalidStockCommandException.class);
    }

    @Test
    @DisplayName("quantity: whole numbers pass (also 2.0 and negatives, judged later), missing is 0, a fraction or overflow is refused")
    void quantity() {
        assertThat(StockCommandMapping.quantity(new BigDecimal("5"))).isEqualTo(5);
        assertThat(StockCommandMapping.quantity(new BigDecimal("2.0"))).isEqualTo(2);
        assertThat(StockCommandMapping.quantity(new BigDecimal("-1"))).isEqualTo(-1);
        assertThat(StockCommandMapping.quantity(BigDecimal.valueOf(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
        assertThat(StockCommandMapping.quantity(null)).isZero();

        assertThatThrownBy(() -> StockCommandMapping.quantity(new BigDecimal("2.7")))
                .isInstanceOf(InvalidStockCommandException.class);
        assertThatThrownBy(() -> StockCommandMapping.quantity(new BigDecimal("1e30")))
                .isInstanceOf(InvalidStockCommandException.class);
    }

    @Test
    @DisplayName("restock: a boolean or missing; any other JSON type is refused and its value is not in the message")
    void restock() {
        assertThat(StockCommandMapping.restock(true)).isTrue();
        assertThat(StockCommandMapping.restock(false)).isFalse();
        assertThat(StockCommandMapping.restock(null)).isNull();

        assertThatThrownBy(() -> StockCommandMapping.restock(1))
                .isInstanceOf(InvalidStockCommandException.class).hasMessageContaining("number");
        assertThatThrownBy(() -> StockCommandMapping.restock("true-secret"))
                .isInstanceOf(InvalidStockCommandException.class).hasMessageContaining("string")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> StockCommandMapping.restock(Map.of("a", 1)))
                .isInstanceOf(InvalidStockCommandException.class).hasMessageContaining("structure");
        assertThatThrownBy(() -> StockCommandMapping.restock(List.of(true)))
                .isInstanceOf(InvalidStockCommandException.class);
    }
}
