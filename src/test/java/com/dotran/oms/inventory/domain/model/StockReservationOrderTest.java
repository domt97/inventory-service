package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StockReservationOrder and ReservationRejection")
class StockReservationOrderTest {

    private StockReservationOrder header(OrderReservationStatus status, Boolean restock) {
        return new StockReservationOrder(OrderId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()),
                StoreId.of(UUID.randomUUID()), status, restock, null);
    }

    @Test
    @DisplayName("REJECTED and CANCELLED are closed; RESERVED and CONFIRMED are open")
    void closedStatuses() {
        assertTrue(header(OrderReservationStatus.REJECTED, null).isClosed());
        assertTrue(header(OrderReservationStatus.CANCELLED, null).isClosed());
        assertFalse(header(OrderReservationStatus.RESERVED, null).isClosed());
        assertFalse(header(OrderReservationStatus.CONFIRMED, null).isClosed());
    }

    @Test
    @DisplayName("the first release is the one that finds restock unset")
    void firstRelease() {
        assertTrue(header(OrderReservationStatus.CANCELLED, null).isFirstRelease());
        assertFalse(header(OrderReservationStatus.CANCELLED, false).isFirstRelease());
    }

    @Test
    @DisplayName("a rejection is either the whole order or some lines")
    void rejectionShape() {
        ReservationRejection.Line line = new ReservationRejection.Line(OrderItemId.of(UUID.randomUUID()),
                SKU.of("SKU-1"), LineRejectionReason.UNKNOWN_SKU);

        assertEquals(OrderRejectionReason.STALE_COMMAND,
                ReservationRejection.ofOrder(OrderRejectionReason.STALE_COMMAND).orderReason());
        assertEquals(List.of(line), ReservationRejection.ofLines(List.of(line)).lines());
        assertThrows(IllegalArgumentException.class, () -> new ReservationRejection(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReservationRejection(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReservationRejection(OrderRejectionReason.ORDER_CLOSED, List.of(line)));
    }

    @Test
    @DisplayName("the rejection keeps its own copy of the lines")
    void defensiveCopy() {
        List<ReservationRejection.Line> lines = new ArrayList<>();
        lines.add(new ReservationRejection.Line(OrderItemId.of(UUID.randomUUID()), SKU.of("SKU-1"),
                LineRejectionReason.INSUFFICIENT_STOCK));
        ReservationRejection rejection = ReservationRejection.ofLines(lines);

        lines.clear();

        assertEquals(1, rejection.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> rejection.lines().clear());
    }
}
