package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.exception.InvalidMovementTypeException;
import com.dotran.oms.inventory.domain.exception.InvalidQuantityException;
import com.dotran.oms.inventory.domain.exception.InventoryNotOrderableException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.core.domain.BaseDomain;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class Inventory extends BaseDomain<InventoryId> {

    private MerchantId merchantId;
    private StoreId storeId;

    private ProductId productId;
    private SKU sku;

    private Long quantity;
    private Long reservedQuantity;

    private InventoryStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * A row created by support through the internal endpoint: no stock and INACTIVE, so not orderable (BR-INV-08) until
     * the catalog's next ProductUpdated sets its status. Catalog-created rows use {@link #forCatalogSku}.
     */
    public void init() {
        this.quantity = 0L;
        this.reservedQuantity = 0L;
        this.status = InventoryStatus.INACTIVE;
        this.createdAt = this.updatedAt = Instant.now();
    }

    /** A new inventory row created from the catalog (ProductCreated / ProductUpdated): no stock, status from the catalog. */
    public static Inventory forCatalogSku(MerchantId merchantId, StoreId storeId, ProductId productId, SKU sku,
                                          InventoryStatus status) {
        Instant now = Instant.now();
        return Inventory.builder()
                .id(InventoryId.newInventoryId())
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(productId)
                .sku(sku)
                .quantity(0L)
                .reservedQuantity(0L)
                .status(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public long availableQuantity() {
        return quantity - reservedQuantity;
    }

    public void receive(Long quantity) {
        validatePositive(quantity);

        this.quantity += quantity;
    }

    /** Domain check before the guarded SQL reserve: INACTIVE is not orderable (BR-INV-08), then stock. */
    public void reserve(Long quantity) {

        validatePositive(quantity);

        if (status != InventoryStatus.ACTIVE) {
            throw new InventoryNotOrderableException();
        }

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

    /** Manual adjustment of on-hand stock; the order hold types are refused (BR-INV-05). Allowed when INACTIVE (BR-INV-10). */
    public void adjust(StockMovementType stockMovementType, Long quantity) {
        if (stockMovementType == null || !stockMovementType.isManualAdjustable()) {
            throw new InvalidMovementTypeException(stockMovementType);
        }
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

    public void validateMerchantAndStore(MerchantId merchantId, StoreId storeId) {
        if (!this.merchantId.equals(merchantId) || !this.storeId.equals(storeId)) {
            throw new ValidationException("Inventory does not belong to the specified merchant or store");
        }
    }
}
