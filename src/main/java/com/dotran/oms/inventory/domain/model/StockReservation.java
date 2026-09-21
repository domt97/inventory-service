package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.inventory.common.domain.valueobject.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.exception.InvalidReservationStateException;
import com.dotran.oms.core.domain.BaseDomain;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
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
    private Instant createdAt;
    private Instant updatedAt;


    public static StockReservation reserve(TenantId tenantId,
                                               StoreId storeId,
                                               OrderId orderId,
                                               OrderItemId orderItemId,
                                               InventoryId inventoryId,
                                               long quantity) {
        Instant now = Instant.now();
        return StockReservation.builder()
                .tenantId(tenantId)
                .storeId(storeId)
                .orderId(orderId)
                .orderItemId(orderItemId)
                .inventoryId(inventoryId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plus(30, ChronoUnit.MINUTES))
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

    public void expire() {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException();
        }

        status = ReservationStatus.EXPIRED;
        updatedAt = Instant.now();
    }
}
