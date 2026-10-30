package com.dotran.oms.inventory.domain.model;

import com.dotran.oms.core.exception.ValidationException;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.exception.InsufficientStockException;
import com.dotran.oms.inventory.domain.exception.InvalidQuantityException;
import com.dotran.oms.inventory.domain.exception.InvalidReservationException;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Inventory Domain Model")
class InventoryTest {

    private static final UUID MERCHANT_UUID = UUID.randomUUID();
    private static final UUID STORE_UUID = UUID.randomUUID();
    private static final UUID PRODUCT_UUID = UUID.randomUUID();
    private static final UUID INVENTORY_UUID = UUID.randomUUID();

    private MerchantId merchantId;
    private StoreId storeId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(MERCHANT_UUID);
        storeId = StoreId.of(STORE_UUID);
    }

    private Inventory buildInventory(long quantity, long reservedQuantity) {
        return Inventory.builder()
                .id(InventoryId.of(INVENTORY_UUID))
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(PRODUCT_UUID))
                .sku(SKU.of("SKU-001"))
                .quantity(quantity)
                .reservedQuantity(reservedQuantity)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("availableQuantity()")
    class AvailableQuantity {

        @Test
        @DisplayName("returns quantity minus reservedQuantity")
        void shouldReturnComputedAvailable() {
            Inventory inventory = buildInventory(100L, 30L);
            assertEquals(70L, inventory.availableQuantity());
        }

        @Test
        @DisplayName("returns zero when fully reserved")
        void shouldReturnZeroWhenFullyReserved() {
            Inventory inventory = buildInventory(50L, 50L);
            assertEquals(0L, inventory.availableQuantity());
        }
    }

    @Nested
    @DisplayName("reserve()")
    class Reserve {

        @Test
        @DisplayName("increases reservedQuantity when sufficient stock available")
        void shouldIncreaseReservedQuantity() {
            Inventory inventory = buildInventory(100L, 20L);

            inventory.reserve(30L);

            assertEquals(50L, inventory.getReservedQuantity());
            assertEquals(100L, inventory.getQuantity());
        }

        @Test
        @DisplayName("throws InsufficientStockException when available stock is not enough")
        void shouldThrowWhenInsufficientStock() {
            Inventory inventory = buildInventory(100L, 80L); // available = 20

            assertThrows(InsufficientStockException.class, () -> inventory.reserve(30L));
        }

        @Test
        @DisplayName("throws InsufficientStockException when available stock is exactly zero")
        void shouldThrowWhenNoAvailableStock() {
            Inventory inventory = buildInventory(50L, 50L);

            assertThrows(InsufficientStockException.class, () -> inventory.reserve(1L));
        }

        @Test
        @DisplayName("succeeds when quantity equals exactly available stock")
        void shouldSucceedWhenQuantityEqualsAvailable() {
            Inventory inventory = buildInventory(100L, 60L); // available = 40

            assertDoesNotThrow(() -> inventory.reserve(40L));
            assertEquals(100L, inventory.getReservedQuantity());
        }

        @Test
        @DisplayName("throws InvalidQuantityException when quantity is zero")
        void shouldThrowWhenQuantityIsZero() {
            Inventory inventory = buildInventory(100L, 0L);

            assertThrows(InvalidQuantityException.class, () -> inventory.reserve(0L));
        }

        @Test
        @DisplayName("throws InvalidQuantityException when quantity is negative")
        void shouldThrowWhenQuantityIsNegative() {
            Inventory inventory = buildInventory(100L, 0L);

            assertThrows(InvalidQuantityException.class, () -> inventory.reserve(-10L));
        }
    }

    @Nested
    @DisplayName("release()")
    class Release {

        @Test
        @DisplayName("decreases reservedQuantity when sufficient reservation exists")
        void shouldDecreaseReservedQuantity() {
            Inventory inventory = buildInventory(100L, 50L);

            inventory.release(20L);

            assertEquals(30L, inventory.getReservedQuantity());
            assertEquals(100L, inventory.getQuantity());
        }

        @Test
        @DisplayName("throws InvalidReservationException when releasing more than reserved")
        void shouldThrowWhenReleasingMoreThanReserved() {
            Inventory inventory = buildInventory(100L, 10L);

            assertThrows(InvalidReservationException.class, () -> inventory.release(20L));
        }

        @Test
        @DisplayName("succeeds when quantity equals exactly reservedQuantity")
        void shouldSucceedWhenQuantityEqualsReserved() {
            Inventory inventory = buildInventory(100L, 30L);

            assertDoesNotThrow(() -> inventory.release(30L));
            assertEquals(0L, inventory.getReservedQuantity());
        }

        @Test
        @DisplayName("throws InvalidQuantityException when quantity is zero")
        void shouldThrowWhenQuantityIsZero() {
            Inventory inventory = buildInventory(100L, 50L);

            assertThrows(InvalidQuantityException.class, () -> inventory.release(0L));
        }
    }

    @Nested
    @DisplayName("confirmReservation()")
    class ConfirmReservation {

        @Test
        @DisplayName("decreases both reservedQuantity and quantity")
        void shouldDecreaseBothReservedAndTotal() {
            Inventory inventory = buildInventory(100L, 50L);

            inventory.confirmReservation(30L);

            assertEquals(20L, inventory.getReservedQuantity());
            assertEquals(70L, inventory.getQuantity());
        }

        @Test
        @DisplayName("throws InvalidReservationException when confirming more than reserved")
        void shouldThrowWhenConfirmingMoreThanReserved() {
            Inventory inventory = buildInventory(100L, 10L);

            assertThrows(InvalidReservationException.class, () -> inventory.confirmReservation(20L));
        }

        @Test
        @DisplayName("throws InvalidQuantityException when quantity is zero")
        void shouldThrowWhenQuantityIsZero() {
            Inventory inventory = buildInventory(100L, 50L);

            assertThrows(InvalidQuantityException.class, () -> inventory.confirmReservation(0L));
        }
    }

    @Nested
    @DisplayName("adjust()")
    class Adjust {

        @Test
        @DisplayName("STOCK_IN increases quantity")
        void shouldIncreaseQuantityOnStockIn() {
            Inventory inventory = buildInventory(100L, 20L);

            inventory.adjust(StockMovementType.ADJUSTMENT_IN, 50L);

            assertEquals(150L, inventory.getQuantity());
            assertEquals(20L, inventory.getReservedQuantity());
        }

        @Test
        @DisplayName("STOCK_OUT decreases quantity when available stock is sufficient")
        void shouldDecreaseQuantityOnStockOut() {
            Inventory inventory = buildInventory(100L, 20L); // available = 80

            inventory.adjust(StockMovementType.ADJUSTMENT_OUT, 50L);

            assertEquals(50L, inventory.getQuantity());
            assertEquals(20L, inventory.getReservedQuantity());
        }

        @Test
        @DisplayName("STOCK_OUT throws InsufficientStockException when it would go below reservedQuantity")
        void shouldThrowOnStockOutBelowReserved() {
            Inventory inventory = buildInventory(100L, 80L); // available = 20

            assertThrows(InsufficientStockException.class,
                    () -> inventory.adjust(StockMovementType.ADJUSTMENT_OUT, 30L));
        }

        @Test
        @DisplayName("STOCK_OUT succeeds when quantity exactly reaches reservedQuantity")
        void shouldSucceedWhenStockOutEqualsAvailable() {
            Inventory inventory = buildInventory(100L, 60L); // available = 40

            assertDoesNotThrow(() -> inventory.adjust(StockMovementType.ADJUSTMENT_OUT, 40L));
            assertEquals(60L, inventory.getQuantity());
        }

        @Test
        @DisplayName("throws InvalidQuantityException when quantity is zero")
        void shouldThrowWhenQuantityIsZero() {
            Inventory inventory = buildInventory(100L, 0L);

            assertThrows(InvalidQuantityException.class,
                    () -> inventory.adjust(StockMovementType.ADJUSTMENT_IN, 0L));
        }
    }

    @Nested
    @DisplayName("validateMerchantAndStore()")
    class ValidateMerchantAndStore {

        @Test
        @DisplayName("does not throw when merchant and store match")
        void shouldNotThrowWhenValid() {
            Inventory inventory = buildInventory(100L, 0L);

            assertDoesNotThrow(() -> inventory.validateMerchantAndStore(merchantId, storeId));
        }

        @Test
        @DisplayName("throws ValidationException when merchantId does not match")
        void shouldThrowWhenMerchantMismatch() {
            Inventory inventory = buildInventory(100L, 0L);
            MerchantId otherMerchant = MerchantId.of(UUID.randomUUID());

            assertThrows(ValidationException.class,
                    () -> inventory.validateMerchantAndStore(otherMerchant, storeId));
        }

        @Test
        @DisplayName("throws ValidationException when storeId does not match")
        void shouldThrowWhenStoreMismatch() {
            Inventory inventory = buildInventory(100L, 0L);
            StoreId otherStore = StoreId.of(UUID.randomUUID());

            assertThrows(ValidationException.class,
                    () -> inventory.validateMerchantAndStore(merchantId, otherStore));
        }
    }

    @Nested
    @DisplayName("init()")
    class Init {

        @Test
        @DisplayName("sets quantity and reservedQuantity to zero with ACTIVE status")
        void shouldInitializeWithDefaults() {
            Inventory inventory = Inventory.builder()
                    .merchantId(merchantId)
                    .storeId(storeId)
                    .productId(ProductId.of(PRODUCT_UUID))
                    .sku(SKU.of("SKU-NEW"))
                    .build();

            inventory.init();

            assertEquals(0L, inventory.getQuantity());
            assertEquals(0L, inventory.getReservedQuantity());
            assertEquals(InventoryStatus.ACTIVE, inventory.getStatus());
            assertNotNull(inventory.getCreatedAt());
            assertNotNull(inventory.getUpdatedAt());
        }
    }

    @Test
    @DisplayName("forCatalogSku creates a row with no stock, a new id and the given status")
    void forCatalogSku_createsRowWithoutStock() {
        Inventory inventory = Inventory.forCatalogSku(
                MerchantId.newMerchantId(), StoreId.newStoreId(),
                ProductId.newProductId(), SKU.of("SKU-1"),
                InventoryStatus.INACTIVE);

        assertThat(inventory.getId()).isNotNull();
        assertThat(inventory.getQuantity()).isZero();
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getStatus()).isEqualTo(InventoryStatus.INACTIVE);
    }
}
