package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.core.domain.BaseDomain;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class StockReservation extends BaseDomain<StockReservationId> {

    private MerchantId merchantId;
    private StoreId storeId;

    private InventoryId inventoryId;

    private OrderId orderId;
    private OrderItemId orderItemId;

    private long quantity;

    private ReservationStatus status;

    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;


    public static StockReservation reserve(MerchantId merchantId,
                                               StoreId storeId,
                                               OrderId orderId,
                                               OrderItemId orderItemId,
                                               InventoryId inventoryId,
                                               long quantity,
                                               Instant now,
                                               Duration ttl) {
        return StockReservation.builder()
                .merchantId(merchantId)
                .storeId(storeId)
                .orderId(orderId)
                .orderItemId(orderItemId)
                .inventoryId(inventoryId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
    }

    public void release() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.RELEASED;
        updatedAt = Instant.now();
    }

    public void confirm() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.CONFIRMED;
        updatedAt = Instant.now();
    }

    /** Confirmed goods put back into stock by a release with restock (BR-INV-03, BR-ORD-09). */
    public void restock() {
        if (status != ReservationStatus.CONFIRMED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.RETURNED;
        updatedAt = Instant.now();
    }

    public void expire() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.EXPIRED;
        updatedAt = Instant.now();
    }
}
