package com.dotran.oms.inventory.application.service;

import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.SkuSnapshot;
import com.dotran.oms.inventory.application.command.SyncProductInventoryCmd.Source;
import com.dotran.oms.inventory.application.repository.InventoryRepository;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository;
import com.dotran.oms.inventory.application.repository.ProductCatalogVersionRepository.ProductCatalogVersion;
import com.dotran.oms.inventory.domain.enums.InventoryStatus;
import com.dotran.oms.inventory.domain.enums.ProductSkuStatus;
import com.dotran.oms.inventory.domain.enums.ProductStatus;
import com.dotran.oms.inventory.domain.exception.CatalogOwnershipMismatchException;
import com.dotran.oms.inventory.domain.exception.InvalidCatalogEventException;
import com.dotran.oms.inventory.domain.model.Inventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncProductInventoryService")
class SyncProductInventoryServiceTest {

    @Mock private ProductCatalogVersionRepository catalogVersionRepository;
    @Mock private InventoryRepository inventoryRepository;

    @InjectMocks
    private SyncProductInventoryService service;

    private final MerchantId merchantId = MerchantId.newMerchantId();
    private final StoreId storeId = StoreId.newStoreId();
    private final ProductId productId = ProductId.newProductId();

    private static SkuSnapshot sku(String code, ProductSkuStatus status) {
        return new SkuSnapshot(SKU.of(code), status);
    }

    private SyncProductInventoryCmd updated(long version, ProductStatus productStatus, SkuSnapshot... skus) {
        return SyncProductInventoryCmd.builder()
                .source(Source.PRODUCT_UPDATED)
                .merchantId(merchantId).storeId(storeId).productId(productId)
                .version(version).productStatus(productStatus)
                .skus(new ArrayList<>(List.of(skus)))
                .build();
    }

    private SyncProductInventoryCmd created(SkuSnapshot... skus) {
        return SyncProductInventoryCmd.builder()
                .source(Source.PRODUCT_CREATED)
                .merchantId(merchantId).storeId(storeId).productId(productId)
                .version(0L).productStatus(ProductStatus.ACTIVE)
                .skus(new ArrayList<>(List.of(skus)))
                .build();
    }

    private Inventory row(String code, InventoryStatus status) {
        return row(InventoryId.newInventoryId(), code, status, merchantId, storeId, productId);
    }

    private Inventory row(InventoryId id, String code, InventoryStatus status, MerchantId merchant, StoreId store, ProductId product) {
        return Inventory.builder()
                .id(id).merchantId(merchant).storeId(store).productId(product)
                .sku(SKU.of(code)).quantity(5L).reservedQuantity(1L).status(status)
                .build();
    }

    private void gateOpen(long version) {
        when(catalogVersionRepository.advance(productId, merchantId, storeId, version)).thenReturn(true);
    }

    private List<Inventory> insertedRows() {
        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository, atLeastOnce()).insertIfAbsent(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("version gate")
    class Gate {

        @Test
        @DisplayName("a stale or duplicate event (gate closed, same owner) writes nothing")
        void staleEvent_writesNothing() {
            when(catalogVersionRepository.advance(productId, merchantId, storeId, 3L)).thenReturn(false);
            when(catalogVersionRepository.findByProductId(productId))
                    .thenReturn(Optional.of(new ProductCatalogVersion(productId, merchantId, storeId, 5L)));

            service.sync(updated(3, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE)));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("a ProductCreated after a ProductUpdated of the same product is skipped")
        void createdAfterUpdated_isSkipped() {
            when(catalogVersionRepository.advance(productId, merchantId, storeId, 0L)).thenReturn(false);
            when(catalogVersionRepository.findByProductId(productId))
                    .thenReturn(Optional.of(new ProductCatalogVersion(productId, merchantId, storeId, 2L)));

            service.sync(created(sku("A", ProductSkuStatus.ACTIVE)));

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("gate closed because the product is recorded for another merchant → mismatch, nothing written")
        void gateMismatch_throws() {
            when(catalogVersionRepository.advance(productId, merchantId, storeId, 2L)).thenReturn(false);
            when(catalogVersionRepository.findByProductId(productId))
                    .thenReturn(Optional.of(new ProductCatalogVersion(productId, MerchantId.newMerchantId(), storeId, 1L)));

            assertThatThrownBy(() -> service.sync(updated(2, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE))))
                    .isInstanceOf(CatalogOwnershipMismatchException.class);

            verifyNoInteractions(inventoryRepository);
        }

        @Test
        @DisplayName("gate closed because the product is recorded for another store → mismatch")
        void gateStoreMismatch_throws() {
            when(catalogVersionRepository.advance(productId, merchantId, storeId, 2L)).thenReturn(false);
            when(catalogVersionRepository.findByProductId(productId))
                    .thenReturn(Optional.of(new ProductCatalogVersion(productId, merchantId, StoreId.newStoreId(), 1L)));

            assertThatThrownBy(() -> service.sync(updated(2, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE))))
                    .isInstanceOf(CatalogOwnershipMismatchException.class);

            verifyNoInteractions(inventoryRepository);
        }
    }

    @Nested
    @DisplayName("ProductUpdated")
    class Updated {

        @Test
        @DisplayName("before ProductCreated (no rows yet) creates every SKU with its target status")
        void beforeCreated_createsAllSkus() {
            gateOpen(1);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of());
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);

            service.sync(updated(1, ProductStatus.ACTIVE,
                    sku("B", ProductSkuStatus.INACTIVE), sku("A", ProductSkuStatus.ACTIVE)));

            assertThat(insertedRows())
                    .extracting(i -> i.getSku().getValue(), Inventory::getStatus, Inventory::getQuantity, Inventory::getReservedQuantity)
                    .containsExactly(
                            tuple("A", InventoryStatus.ACTIVE, 0L, 0L),
                            tuple("B", InventoryStatus.INACTIVE, 0L, 0L));
        }

        @Test
        @DisplayName("sets each existing row to its target status: removed, INACTIVE SKU, revived")
        void setsStatusPerRow() {
            Inventory keep = row("KEEP", InventoryStatus.ACTIVE);
            Inventory removed = row("GONE", InventoryStatus.ACTIVE);
            Inventory deactivated = row("OFF", InventoryStatus.ACTIVE);
            Inventory revived = row("BACK", InventoryStatus.INACTIVE);
            List<Inventory> rows = List.of(keep, removed, deactivated, revived);
            gateOpen(4);
            when(inventoryRepository.getByProductId(productId)).thenReturn(rows);
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(keep, deactivated, revived));
            when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

            service.sync(updated(4, ProductStatus.ACTIVE,
                    sku("KEEP", ProductSkuStatus.ACTIVE), sku("OFF", ProductSkuStatus.INACTIVE), sku("BACK", ProductSkuStatus.ACTIVE)));

            verify(inventoryRepository).updateStatus(removed.getId(), merchantId, storeId, productId, InventoryStatus.INACTIVE);
            verify(inventoryRepository).updateStatus(deactivated.getId(), merchantId, storeId, productId, InventoryStatus.INACTIVE);
            verify(inventoryRepository).updateStatus(revived.getId(), merchantId, storeId, productId, InventoryStatus.ACTIVE);
            verify(inventoryRepository, never()).updateStatus(eq(keep.getId()), any(), any(), any(), any());
            verify(inventoryRepository, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("a DRAFT or INACTIVE product makes every row INACTIVE")
        void inactiveProduct_allRowsInactive() {
            for (ProductStatus productStatus : List.of(ProductStatus.DRAFT, ProductStatus.INACTIVE)) {
                reset(catalogVersionRepository, inventoryRepository);
                Inventory a = row("A", InventoryStatus.ACTIVE);
                Inventory b = row("B", InventoryStatus.ACTIVE);
                gateOpen(2);
                when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(a, b));
                when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(a, b));
                when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

                service.sync(updated(2, productStatus, sku("A", ProductSkuStatus.ACTIVE), sku("B", ProductSkuStatus.ACTIVE)));

                verify(inventoryRepository, times(2)).updateStatus(any(), eq(merchantId), eq(storeId), eq(productId), eq(InventoryStatus.INACTIVE));
            }
        }

        @Test
        @DisplayName("an empty snapshot makes every row INACTIVE without querying by SKU")
        void emptySnapshot_allRowsInactive() {
            Inventory a = row("A", InventoryStatus.ACTIVE);
            gateOpen(3);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(a));
            when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

            service.sync(updated(3, ProductStatus.ACTIVE));

            verify(inventoryRepository).updateStatus(a.getId(), merchantId, storeId, productId, InventoryStatus.INACTIVE);
            verify(inventoryRepository, never()).getByStoreIdAndSKUs(any(), anyList());
            verify(inventoryRepository, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("status updates follow Java UUID order (not Postgres order)")
        void statusUpdates_inJavaUuidOrder() {
            // Java compares UUIDs as signed longs: 8000... sorts before 0000..., Postgres sorts the other way.
            InventoryId high = InventoryId.of(UUID.fromString("80000000-0000-0000-0000-000000000001"));
            InventoryId low = InventoryId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            Inventory lowRow = row(low, "A", InventoryStatus.ACTIVE, merchantId, storeId, productId);
            Inventory highRow = row(high, "B", InventoryStatus.ACTIVE, merchantId, storeId, productId);
            gateOpen(2);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(lowRow, highRow));
            when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

            service.sync(updated(2, ProductStatus.INACTIVE));

            InOrder order = inOrder(inventoryRepository);
            order.verify(inventoryRepository).updateStatus(eq(high), any(), any(), any(), any());
            order.verify(inventoryRepository).updateStatus(eq(low), any(), any(), any(), any());
        }

        @Test
        @DisplayName("missing SKUs are inserted sorted by code; existing ones are not inserted again")
        void insertsMissingSorted() {
            Inventory existing = row("M", InventoryStatus.ACTIVE);
            gateOpen(2);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(existing));
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(existing));
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);

            service.sync(updated(2, ProductStatus.ACTIVE,
                    sku("Z", ProductSkuStatus.ACTIVE), sku("M", ProductSkuStatus.ACTIVE), sku("C", ProductSkuStatus.ACTIVE)));

            assertThat(insertedRows()).extracting(i -> i.getSku().getValue()).containsExactly("C", "Z");
        }

        @Test
        @DisplayName("a SKU owned by another product of the store is skipped and that row is untouched")
        void skuOfOtherProduct_isSkipped() {
            Inventory foreign = row(InventoryId.newInventoryId(), "SHARED", InventoryStatus.ACTIVE, merchantId, storeId, ProductId.newProductId());
            gateOpen(2);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of());
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(foreign));

            service.sync(updated(2, ProductStatus.INACTIVE, sku("SHARED", ProductSkuStatus.ACTIVE)));

            verify(inventoryRepository, never()).insertIfAbsent(any());
            verify(inventoryRepository, never()).updateStatus(eq(foreign.getId()), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an own row recorded for another merchant → mismatch, nothing inserted or updated")
        void ownRowOtherMerchant_throws() {
            Inventory alien = row(InventoryId.newInventoryId(), "A", InventoryStatus.ACTIVE, MerchantId.newMerchantId(), storeId, productId);
            gateOpen(2);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(alien));

            assertThatThrownBy(() -> service.sync(updated(2, ProductStatus.ACTIVE, sku("B", ProductSkuStatus.ACTIVE))))
                    .isInstanceOf(CatalogOwnershipMismatchException.class);

            verify(inventoryRepository, never()).insertIfAbsent(any());
            verify(inventoryRepository, never()).updateStatus(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an own row in another store → mismatch")
        void ownRowOtherStore_throws() {
            Inventory alien = row(InventoryId.newInventoryId(), "A", InventoryStatus.ACTIVE, merchantId, StoreId.newStoreId(), productId);
            gateOpen(2);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(alien));

            assertThatThrownBy(() -> service.sync(updated(2, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE))))
                    .isInstanceOf(CatalogOwnershipMismatchException.class);

            verify(inventoryRepository, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("a SKU code of 100 characters and 100 SKUs are accepted")
        void limitsAccepted() {
            gateOpen(1);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of());
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);
            SkuSnapshot[] skus = IntStream.range(0, 100)
                    .mapToObj(i -> sku(i == 0 ? "X".repeat(100) : "SKU-" + i, ProductSkuStatus.ACTIVE))
                    .toArray(SkuSnapshot[]::new);

            service.sync(updated(1, ProductStatus.ACTIVE, skus));

            verify(inventoryRepository, times(100)).insertIfAbsent(any());
        }

        @Test
        @DisplayName("duplicate SKU codes with the same status are de-duplicated")
        void duplicateSameStatus_deduplicated() {
            gateOpen(1);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of());
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);

            service.sync(updated(1, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE), sku("A", ProductSkuStatus.ACTIVE)));

            verify(inventoryRepository, times(1)).insertIfAbsent(any());
        }

        @Test
        @DisplayName("an insert lost to a concurrent writer of the same product: the row is re-read and its status set")
        void insertLostToConcurrentWriter_statusStillSet() {
            Inventory createdElsewhere = row("A", InventoryStatus.ACTIVE);
            gateOpen(1);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(), List.of(createdElsewhere));
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(0);
            when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

            service.sync(updated(1, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.INACTIVE)));

            verify(inventoryRepository).updateStatus(createdElsewhere.getId(), merchantId, storeId, productId, InventoryStatus.INACTIVE);
        }

        @Test
        @DisplayName("a row of another store that appears between the two reads → mismatch after the insert, no status update")
        void mismatchOnSecondRead_throwsBeforeStatusUpdates() {
            Inventory alien = row(InventoryId.newInventoryId(), "B", InventoryStatus.ACTIVE, merchantId, StoreId.newStoreId(), productId);
            gateOpen(1);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(), List.of(alien));
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);

            assertThatThrownBy(() -> service.sync(updated(1, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE))))
                    .isInstanceOf(CatalogOwnershipMismatchException.class);

            verify(inventoryRepository, never()).updateStatus(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("ProductCreated")
    class Created {

        @Test
        @DisplayName("creates missing SKUs ACTIVE and never changes the status of existing rows")
        void createsMissing_neverUpdatesStatus() {
            Inventory existing = row("A", InventoryStatus.INACTIVE);
            gateOpen(0);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(existing));
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(existing));
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);

            service.sync(created(sku("A", ProductSkuStatus.ACTIVE), sku("B", ProductSkuStatus.ACTIVE)));

            assertThat(insertedRows()).extracting(i -> i.getSku().getValue(), Inventory::getStatus)
                    .containsExactly(tuple("B", InventoryStatus.ACTIVE));
            verify(inventoryRepository, never()).updateStatus(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("is not capped at 100 SKUs (create and import have no cap yet)")
        void moreThan100Skus_allInserted() {
            gateOpen(0);
            when(inventoryRepository.getByProductId(productId)).thenReturn(List.of());
            when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of());
            when(inventoryRepository.insertIfAbsent(any())).thenReturn(1);
            SkuSnapshot[] skus = IntStream.range(0, 101).mapToObj(i -> sku("SKU-" + i, ProductSkuStatus.ACTIVE)).toArray(SkuSnapshot[]::new);

            service.sync(created(skus));

            verify(inventoryRepository, times(101)).insertIfAbsent(any());
        }
    }

    @Nested
    @DisplayName("invalid events (never retried, nothing written)")
    class Invalid {

        private void assertInvalid(SyncProductInventoryCmd cmd) {
            assertThatThrownBy(() -> service.sync(cmd)).isInstanceOf(InvalidCatalogEventException.class);
            verifyNoInteractions(catalogVersionRepository, inventoryRepository);
        }

        @Test
        @DisplayName("ProductUpdated with version 0")
        void updatedVersionZero() {
            assertInvalid(updated(0, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE)));
        }

        @Test
        @DisplayName("ProductCreated with a version other than 0")
        void createdVersionNotZero() {
            SyncProductInventoryCmd cmd = created(sku("A", ProductSkuStatus.ACTIVE));
            cmd.setVersion(1L);
            assertInvalid(cmd);
        }

        @Test
        @DisplayName("ProductUpdated with 101 SKUs")
        void updatedTooManySkus() {
            SkuSnapshot[] skus = IntStream.range(0, 101).mapToObj(i -> sku("SKU-" + i, ProductSkuStatus.ACTIVE)).toArray(SkuSnapshot[]::new);
            assertInvalid(updated(1, ProductStatus.ACTIVE, skus));
        }

        @Test
        @DisplayName("a blank or 101-character SKU code")
        void badSkuCode() {
            assertInvalid(updated(1, ProductStatus.ACTIVE, sku(" ", ProductSkuStatus.ACTIVE)));
            assertInvalid(updated(1, ProductStatus.ACTIVE, sku("X".repeat(101), ProductSkuStatus.ACTIVE)));
        }

        @Test
        @DisplayName("the same SKU code twice with different statuses")
        void conflictingDuplicates() {
            assertInvalid(updated(1, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE), sku("A", ProductSkuStatus.INACTIVE)));
        }

        @Test
        @DisplayName("a missing merchant")
        void missingMerchant() {
            SyncProductInventoryCmd cmd = updated(1, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.ACTIVE));
            cmd.setMerchantId(null);
            assertInvalid(cmd);
        }
    }

    @Test
    @DisplayName("ProductUpdated never writes quantities (status changes go through the guarded status update only)")
    void neverWritesQuantities() {
        Inventory a = row("A", InventoryStatus.ACTIVE);
        gateOpen(2);
        when(inventoryRepository.getByProductId(productId)).thenReturn(List.of(a));
        when(inventoryRepository.getByStoreIdAndSKUs(eq(storeId), anyList())).thenReturn(List.of(a));
        when(inventoryRepository.updateStatus(any(), any(), any(), any(), any())).thenReturn(1);

        service.sync(updated(2, ProductStatus.ACTIVE, sku("A", ProductSkuStatus.INACTIVE)));

        verify(inventoryRepository, never()).update(any());
        verify(inventoryRepository, never()).updateBatch(anyList());
        verify(inventoryRepository, never()).stockIn(any(), any(), any(), anyLong());
        verify(inventoryRepository, never()).stockOut(any(), any(), any(), anyLong());
    }
}
