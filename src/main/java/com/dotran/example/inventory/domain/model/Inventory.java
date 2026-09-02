package com.dotran.example.inventory.domain.model;

import com.dotran.example.inventory.common.domain.BaseDomain;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.enums.InventoryStatus;
import com.dotran.example.inventory.domain.enums.StockMovementType;
import com.dotran.example.inventory.domain.exception.InsufficientStockException;
import com.dotran.example.inventory.domain.exception.InvalidQuantityException;
import com.dotran.example.inventory.domain.exception.InvalidReservationException;
import com.dotran.example.inventory.domain.exception.ValidationException;
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

    private Long quantity;
    private Long reservedQuantity;

    private InventoryStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    public void init() {
        this.quantity = 0L;
        this.reservedQuantity = 0L;
        this.status = InventoryStatus.ACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }

    public long availableQuantity() {
        return quantity - reservedQuantity;
    }

    public void receive(Long quantity) {
        validatePositive(quantity);

        this.quantity += quantity;
    }

    public void reserve(Long quantity) {

        validatePositive(quantity);

        if (availableQuantity() < quantity) {
            throw new InsufficientStockException();
        }

        this.reservedQuantity += quantity;
    }

    public void release(Long quantity) {

        validatePositive(quantity);

        if (reservedQuantity < quantity) {
            throw new InvalidReservationException();
        }

        this.reservedQuantity -= quantity;
    }

    public void confirmReservation(Long quantity) {

        validatePositive(quantity);

        if (reservedQuantity < quantity) {
            throw new InvalidReservationException();
        }

        this.reservedQuantity -= quantity;
        this.quantity -= quantity;
    }

    public void adjust(StockMovementType stockMovementType, Long quantity) {
        validatePositive(quantity);

        Long adjustQuantity = stockMovementType.isStockIn() ? quantity : -quantity;

        if (this.quantity + adjustQuantity < this.reservedQuantity) {
            throw new InsufficientStockException();
        }

        this.quantity += adjustQuantity;
        this.updatedAt = Instant.now();
    }

    private void validatePositive(Long quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException();
        }
    }

    public void validateTenantAndStore(TenantId tenantId, StoreId storeId) {
        if (!this.tenantId.equals(tenantId) || !this.storeId.equals(storeId)) {
            throw new ValidationException("Inventory does not belong to the specified tenant or store");
        }
    }
}
