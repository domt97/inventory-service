package com.dotran.example.inventory.domain.model;

import com.dotran.example.inventory.common.domain.BaseDomain;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.enums.InventoryStatus;
import com.dotran.example.inventory.domain.exception.InsufficientStockException;
import com.dotran.example.inventory.domain.exception.InvalidQuantityException;
import com.dotran.example.inventory.domain.exception.InvalidReservationException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class Inventory extends BaseDomain<InventoryId> {

    private TenantId tenantId;
    private StoreId storeId;

    private ProductId productId;
    private SKU sku;

    private long quantity;
    private long reservedQuantity;

    private InventoryStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    public void init() {
        this.quantity = 0;
        this.reservedQuantity = 0;
        this.status = InventoryStatus.ACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }

    public long availableQuantity() {
        return quantity - reservedQuantity;
    }

    public void receive(long quantity) {
        validatePositive(quantity);

        this.quantity += quantity;
    }

    public void reserve(long quantity) {

        validatePositive(quantity);

        if (availableQuantity() < quantity) {
            throw new InsufficientStockException();
        }

        this.reservedQuantity += quantity;
    }

    public void release(long quantity) {

        validatePositive(quantity);

        if (reservedQuantity < quantity) {
            throw new InvalidReservationException();
        }

        this.reservedQuantity -= quantity;
    }

    public void confirmReservation(long quantity) {

        validatePositive(quantity);

        if (reservedQuantity < quantity) {
            throw new InvalidReservationException();
        }

        this.reservedQuantity -= quantity;
        this.quantity -= quantity;
    }

    public void adjust(long quantity) {
        if (this.quantity + quantity < this.reservedQuantity) {
            throw new InsufficientStockException();
        }

        this.quantity += quantity;
    }

    private void validatePositive(long quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException();
        }
    }
}
