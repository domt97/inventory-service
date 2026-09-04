package com.dotran.example.inventory.domain.model;

import com.dotran.example.inventory.common.domain.BaseDomain;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.OrderItemId;
import com.dotran.example.inventory.common.domain.valueobject.StockReservationId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.enums.ReservationStatus;
import com.dotran.example.inventory.domain.exception.InvalidReservationStateException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class StockReservation extends BaseDomain<StockReservationId> {

    private TenantId tenantId;
    private StoreId storeId;

    private InventoryId inventoryId;

    private OrderId orderId;
    private OrderItemId orderItemId;

    private long quantity;

    private ReservationStatus status;

    private Instant expiresAt;


    public void reserve() {
        status = ReservationStatus.RESERVED;
        expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
    }

    public void release() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.RELEASED;
    }

    public void confirm() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.CONFIRMED;
    }
}
