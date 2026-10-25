package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.model.Inventory;
import com.dotran.oms.inventory.infrastructure.mapper.InventoryPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataInventoryRepository;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dotran.oms.core.exception.NotFoundException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryPersistenceAdapter")
class InventoryPersistenceAdapterTest {

    @Mock
    private SpringDataInventoryRepository springDataInventoryRepository;

    @Mock
    private InventoryPersistenceMapper mapper;

    @InjectMocks
    private InventoryPersistenceAdapter adapter;

    private UUID inventoryUUID;
    private UUID merchantUUID;
    private UUID storeUUID;
    private InventoryId inventoryId;
    private MerchantId merchantId;
    private StoreId storeId;

    @BeforeEach
    void setUp() {
        inventoryUUID = UUID.randomUUID();
        merchantUUID = UUID.randomUUID();
        storeUUID = UUID.randomUUID();
        inventoryId = InventoryId.of(inventoryUUID);
        merchantId = MerchantId.of(merchantUUID);
        storeId = StoreId.of(storeUUID);
    }

    private Inventory buildInventory() {
        return Inventory.builder()
                .id(inventoryId)
                .merchantId(merchantId)
                .storeId(storeId)
                .productId(ProductId.of(UUID.randomUUID()))
                .sku(SKU.of("SKU-001"))
                .quantity(100L)
                .reservedQuantity(0L)
                .status(InventoryStatus.ACTIVE)
                .build();
    }

    private InventoryEntity buildEntity(UUID id) {
        InventoryEntity entity = new InventoryEntity();
        entity.setId(id);
        return entity;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("maps domain to entity, saves, and returns mapped domain")
        void shouldCreateAndReturnMappedInventory() {
            Inventory inventory = buildInventory();
            InventoryEntity entity = buildEntity(inventoryUUID);
            InventoryEntity saved = buildEntity(inventoryUUID);

            when(mapper.fromInventory(inventory)).thenReturn(entity);
            when(springDataInventoryRepository.saveAndFlush(entity)).thenReturn(saved);
            when(mapper.fromEntity(saved)).thenReturn(inventory);

            Inventory result = adapter.create(inventory);

            assertSame(inventory, result);
            verify(springDataInventoryRepository).saveAndFlush(entity);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("loads existing entity, applies update, saves, and returns mapped domain")
        void shouldUpdateAndReturnMappedInventory() {
            Inventory inventory = buildInventory();
            InventoryEntity existing = buildEntity(inventoryUUID);
            InventoryEntity saved = buildEntity(inventoryUUID);

            when(springDataInventoryRepository.findById(inventoryUUID)).thenReturn(Optional.of(existing));
            when(springDataInventoryRepository.saveAndFlush(existing)).thenReturn(saved);
            when(mapper.fromEntity(saved)).thenReturn(inventory);

            Inventory result = adapter.update(inventory);

            assertSame(inventory, result);
            verify(mapper).updateInventory(inventory, existing);
        }

        @Test
        @DisplayName("throws NotFoundException when inventory to update does not exist")
        void shouldThrowWhenNotFound() {
            Inventory inventory = buildInventory();

            when(springDataInventoryRepository.findById(inventoryUUID)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class, () -> adapter.update(inventory));
            verify(springDataInventoryRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("createBatch")
    class CreateBatch {

        @Test
        @DisplayName("maps all, saves all, and returns all mapped domain objects")
        void shouldCreateBatchAndReturnAll() {
            Inventory inv1 = buildInventory();
            InventoryEntity entity1 = buildEntity(inventoryUUID);
            InventoryEntity saved1 = buildEntity(inventoryUUID);

            when(mapper.fromInventory(inv1)).thenReturn(entity1);
            when(springDataInventoryRepository.saveAllAndFlush(List.of(entity1))).thenReturn(List.of(saved1));
            when(mapper.fromEntity(saved1)).thenReturn(inv1);

            List<Inventory> result = adapter.createBatch(List.of(inv1));

            assertEquals(1, result.size());
            assertSame(inv1, result.get(0));
        }
    }

    @Nested
    @DisplayName("getAllById")
    class GetAllById {

        @Test
        @DisplayName("unwraps all IDs, queries, and returns mapped domain objects")
        void shouldReturnAllMappedById() {
            Inventory inventory = buildInventory();
            InventoryEntity entity = buildEntity(inventoryUUID);

            when(springDataInventoryRepository.findAllById(List.of(inventoryUUID))).thenReturn(List.of(entity));
            when(mapper.fromEntity(entity)).thenReturn(inventory);

            List<Inventory> result = adapter.getAllById(List.of(inventoryId));

            assertEquals(1, result.size());
            assertSame(inventory, result.get(0));
        }
    }

    @Nested
    @DisplayName("getByProductId")
    class GetByProductId {

        @Test
        @DisplayName("unwraps productId UUID and returns mapped domain objects")
        void shouldReturnInventoriesByProductId() {
            UUID productUUID = UUID.randomUUID();
            ProductId productId = ProductId.of(productUUID);
            Inventory inventory = buildInventory();
            InventoryEntity entity = buildEntity(inventoryUUID);

            when(springDataInventoryRepository.findAllByStoreProductId(productUUID)).thenReturn(List.of(entity));
            when(mapper.fromEntity(entity)).thenReturn(inventory);

            List<Inventory> result = adapter.getByProductId(productId);

            assertEquals(1, result.size());
            verify(springDataInventoryRepository).findAllByStoreProductId(productUUID);
        }
    }

    @Nested
    @DisplayName("getByStoreIdAndSKUs")
    class GetByStoreIdAndSKUs {

        @Test
        @DisplayName("unwraps storeId and SKU values, queries, and returns mapped domain objects")
        void shouldReturnInventoriesByStoreAndSkus() {
            SKU sku = SKU.of("SKU-001");
            Inventory inventory = buildInventory();
            InventoryEntity entity = buildEntity(inventoryUUID);

            when(springDataInventoryRepository.findAllByStoreIdAndSkuIn(
                    eq(storeUUID), (Collection<String>) argThat(c -> ((Collection<?>) c).contains("SKU-001"))))
                    .thenReturn(List.of(entity));
            when(mapper.fromEntity(entity)).thenReturn(inventory);

            List<Inventory> result = adapter.getByStoreIdAndSKUs(storeId, List.of(sku));

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("updateBatch")
    class UpdateBatch {

        @Test
        @DisplayName("throws IllegalStateException when some inventory IDs are not found")
        void shouldThrowWhenSomeInventoriesNotFound() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            Inventory inv1 = Inventory.builder()
                    .id(InventoryId.of(id1)).merchantId(merchantId).storeId(storeId)
                    .productId(ProductId.of(UUID.randomUUID())).sku(SKU.of("SKU-A"))
                    .quantity(10L).reservedQuantity(0L).status(InventoryStatus.ACTIVE).build();
            Inventory inv2 = Inventory.builder()
                    .id(InventoryId.of(id2)).merchantId(merchantId).storeId(storeId)
                    .productId(ProductId.of(UUID.randomUUID())).sku(SKU.of("SKU-B"))
                    .quantity(10L).reservedQuantity(0L).status(InventoryStatus.ACTIVE).build();

            // only id1 found in DB
            when(springDataInventoryRepository.findAllById(any())).thenReturn(List.of(buildEntity(id1)));

            assertThrows(IllegalStateException.class,
                    () -> adapter.updateBatch(List.of(inv1, inv2)));
        }

        @Test
        @DisplayName("saves all entities and returns mapped domain objects when all IDs found")
        void shouldSaveAllWhenAllFound() {
            UUID id1 = UUID.randomUUID();

            Inventory inv = Inventory.builder()
                    .id(InventoryId.of(id1)).merchantId(merchantId).storeId(storeId)
                    .productId(ProductId.of(UUID.randomUUID())).sku(SKU.of("SKU-A"))
                    .quantity(10L).reservedQuantity(0L).status(InventoryStatus.ACTIVE).build();

            InventoryEntity entity = buildEntity(id1);
            InventoryEntity savedEntity = buildEntity(id1);

            when(springDataInventoryRepository.findAllById(any())).thenReturn(List.of(entity));
            when(springDataInventoryRepository.saveAll(any())).thenReturn(List.of(savedEntity));
            when(mapper.fromEntity(savedEntity)).thenReturn(inv);

            List<Inventory> result = adapter.updateBatch(List.of(inv));

            assertEquals(1, result.size());
            verify(springDataInventoryRepository).saveAll(any());
        }
    }

    @Nested
    @DisplayName("atomic SQL delegation")
    class AtomicSql {

        @Test
        @DisplayName("reserve unwraps InventoryId and delegates to Spring Data repo")
        void shouldDelegateReserve() {
            when(springDataInventoryRepository.reserve(inventoryUUID, 30L)).thenReturn(1);

            int result = adapter.reserve(inventoryId, 30L);

            assertEquals(1, result);
            verify(springDataInventoryRepository).reserve(inventoryUUID, 30L);
        }

        @Test
        @DisplayName("confirm unwraps InventoryId and delegates to Spring Data repo")
        void shouldDelegateConfirm() {
            when(springDataInventoryRepository.confirm(inventoryUUID, 20L)).thenReturn(1);

            int result = adapter.confirm(inventoryId, 20L);

            assertEquals(1, result);
            verify(springDataInventoryRepository).confirm(inventoryUUID, 20L);
        }

        @Test
        @DisplayName("release unwraps InventoryId and delegates to Spring Data repo")
        void shouldDelegateRelease() {
            when(springDataInventoryRepository.release(inventoryUUID, 10L)).thenReturn(1);

            int result = adapter.release(inventoryId, 10L);

            assertEquals(1, result);
            verify(springDataInventoryRepository).release(inventoryUUID, 10L);
        }

        @Test
        @DisplayName("stockIn unwraps all IDs and delegates to Spring Data repo")
        void shouldDelegateStockIn() {
            when(springDataInventoryRepository.stockIn(inventoryUUID, merchantUUID, storeUUID, 50L)).thenReturn(1);

            int result = adapter.stockIn(inventoryId, merchantId, storeId, 50L);

            assertEquals(1, result);
            verify(springDataInventoryRepository).stockIn(inventoryUUID, merchantUUID, storeUUID, 50L);
        }

        @Test
        @DisplayName("stockOut unwraps all IDs and delegates to Spring Data repo")
        void shouldDelegateStockOut() {
            when(springDataInventoryRepository.stockOut(inventoryUUID, merchantUUID, storeUUID, 50L)).thenReturn(1);

            int result = adapter.stockOut(inventoryId, merchantId, storeId, 50L);

            assertEquals(1, result);
            verify(springDataInventoryRepository).stockOut(inventoryUUID, merchantUUID, storeUUID, 50L);
        }
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("returns mapped inventory when entity found")
        void shouldReturnMappedInventory() {
            Inventory inventory = buildInventory();
            InventoryEntity entity = buildEntity(inventoryUUID);

            when(springDataInventoryRepository.findById(inventoryUUID)).thenReturn(Optional.of(entity));
            when(mapper.fromEntity(entity)).thenReturn(inventory);

            Optional<Inventory> result = adapter.getById(inventoryId);

            assertTrue(result.isPresent());
            assertSame(inventory, result.get());
        }

        @Test
        @DisplayName("returns empty when entity not found")
        void shouldReturnEmptyWhenNotFound() {
            when(springDataInventoryRepository.findById(inventoryUUID)).thenReturn(Optional.empty());

            Optional<Inventory> result = adapter.getById(inventoryId);

            assertTrue(result.isEmpty());
        }
    }
}
