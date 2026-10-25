package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StockReservation Domain Model")
class StockReservationTest {

    private StockReservation buildReservation(ReservationStatus status) {
        return StockReservation.builder()
                .id(new StockReservationId(1L))
                .merchantId(MerchantId.of(UUID.randomUUID()))
                .storeId(StoreId.of(UUID.randomUUID()))
                .inventoryId(InventoryId.of(UUID.randomUUID()))
                .orderId(OrderId.of(UUID.randomUUID()))
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(10L)
                .status(status)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("reserve() factory method")
    class ReserveFactory {

        @Test
        @DisplayName("creates reservation with RESERVED status")
        void shouldCreateWithReservedStatus() {
            StockReservation reservation = StockReservation.reserve(
                    MerchantId.of(UUID.randomUUID()),
                    StoreId.of(UUID.randomUUID()),
                    OrderId.of(UUID.randomUUID()),
                    OrderItemId.of(UUID.randomUUID()),
                    InventoryId.of(UUID.randomUUID()),
                    20L
            );

            assertEquals(ReservationStatus.RESERVED, reservation.getStatus());
            assertEquals(20L, reservation.getQuantity());
            assertNotNull(reservation.getExpiresAt());
            assertNotNull(reservation.getCreatedAt());
        }

        @Test
        @DisplayName("sets expiry to 30 minutes from creation")
        void shouldSetExpiryTo30Minutes() {
            Instant before = Instant.now();

            StockReservation reservation = StockReservation.reserve(
                    MerchantId.of(UUID.randomUUID()),
                    StoreId.of(UUID.randomUUID()),
                    OrderId.of(UUID.randomUUID()),
                    OrderItemId.of(UUID.randomUUID()),
                    InventoryId.of(UUID.randomUUID()),
                    10L
            );

            Instant after = Instant.now();
            assertTrue(reservation.getExpiresAt().isAfter(before.plusSeconds(1799)));
            assertTrue(reservation.getExpiresAt().isBefore(after.plusSeconds(1801)));
        }
    }

    @Nested
    @DisplayName("confirm()")
    class Confirm {

        @Test
        @DisplayName("transitions from RESERVED to CONFIRMED")
        void shouldTransitionToConfirmed() {
            StockReservation reservation = buildReservation(ReservationStatus.RESERVED);

            reservation.confirm();

            assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when already CONFIRMED")
        void shouldThrowWhenAlreadyConfirmed() {
            StockReservation reservation = buildReservation(ReservationStatus.CONFIRMED);

            assertThrows(InvalidReservationStateException.class, reservation::confirm);
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when EXPIRED")
        void shouldThrowWhenExpired() {
            StockReservation reservation = buildReservation(ReservationStatus.EXPIRED);

            assertThrows(InvalidReservationStateException.class, reservation::confirm);
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when RELEASED")
        void shouldThrowWhenReleased() {
            StockReservation reservation = buildReservation(ReservationStatus.RELEASED);

            assertThrows(InvalidReservationStateException.class, reservation::confirm);
        }
    }

    @Nested
    @DisplayName("expire()")
    class Expire {

        @Test
        @DisplayName("transitions from RESERVED to EXPIRED")
        void shouldTransitionToExpired() {
            StockReservation reservation = buildReservation(ReservationStatus.RESERVED);

            reservation.expire();

            assertEquals(ReservationStatus.EXPIRED, reservation.getStatus());
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when already CONFIRMED")
        void shouldThrowWhenConfirmed() {
            StockReservation reservation = buildReservation(ReservationStatus.CONFIRMED);

            assertThrows(InvalidReservationStateException.class, reservation::expire);
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when already EXPIRED")
        void shouldThrowWhenAlreadyExpired() {
            StockReservation reservation = buildReservation(ReservationStatus.EXPIRED);

            assertThrows(InvalidReservationStateException.class, reservation::expire);
        }
    }

    @Nested
    @DisplayName("release()")
    class Release {

        @Test
        @DisplayName("transitions from RESERVED to RELEASED")
        void shouldTransitionToReleased() {
            StockReservation reservation = buildReservation(ReservationStatus.RESERVED);

            reservation.release();

            assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when already EXPIRED")
        void shouldThrowWhenExpired() {
            StockReservation reservation = buildReservation(ReservationStatus.EXPIRED);

            assertThrows(InvalidReservationStateException.class, reservation::release);
        }

        @Test
        @DisplayName("throws InvalidReservationStateException when already CONFIRMED")
        void shouldThrowWhenConfirmed() {
            StockReservation reservation = buildReservation(ReservationStatus.CONFIRMED);

            assertThrows(InvalidReservationStateException.class, reservation::release);
        }
    }
}
