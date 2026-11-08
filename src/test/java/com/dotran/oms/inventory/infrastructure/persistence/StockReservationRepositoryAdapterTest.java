package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.application.repository.ExpiryCursor;
import com.dotran.oms.inventory.application.repository.ReservationLineView;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.infrastructure.mapper.StockReservationPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationRepository;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.MerchantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockReservationRepositoryAdapter")
class StockReservationRepositoryAdapterTest {

    @Mock
    private SpringDataStockReservationRepository springDataStockReservationRepository;

    @Mock
    private StockReservationPersistenceMapper stockReservationPersistenceMapper;

    @InjectMocks
    private StockReservationRepositoryAdapter adapter;

    private MerchantId merchantId;
    private StoreId storeId;
    private InventoryId inventoryId;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        merchantId = MerchantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
        orderId = OrderId.of(UUID.randomUUID());
    }

    private StockReservation buildReservation(long id) {
        return StockReservation.builder()
                .id(new StockReservationId(id))
                .merchantId(merchantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .orderId(orderId)
                .orderItemId(OrderItemId.of(UUID.randomUUID()))
                .quantity(10L)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(1800))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private StockReservationEntity buildEntity(long id) {
        StockReservationEntity entity = new StockReservationEntity();
        entity.setId(id);
        return entity;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("maps domain to entity, saves, and returns mapped domain")
        void shouldCreateAndReturn() {
            StockReservation reservation = buildReservation(1L);
            StockReservationEntity entity = buildEntity(1L);
            StockReservationEntity saved = buildEntity(1L);

            when(stockReservationPersistenceMapper.toEntity(reservation)).thenReturn(entity);
            when(springDataStockReservationRepository.saveAndFlush(entity)).thenReturn(saved);
            when(stockReservationPersistenceMapper.toDomain(saved)).thenReturn(reservation);

            StockReservation result = adapter.create(reservation);

            assertSame(reservation, result);
            verify(springDataStockReservationRepository).saveAndFlush(entity);
        }
    }

    @Nested
    @DisplayName("createBatch")
    class CreateBatch {

        @Test
        @DisplayName("maps all, saves all, and returns all mapped domain objects")
        void shouldCreateBatchAndReturnAll() {
            StockReservation r1 = buildReservation(1L);
            StockReservationEntity entity = buildEntity(1L);
            StockReservationEntity saved = buildEntity(1L);

            when(stockReservationPersistenceMapper.toEntity(r1)).thenReturn(entity);
            when(springDataStockReservationRepository.saveAllAndFlush(List.of(entity))).thenReturn(List.of(saved));
            when(stockReservationPersistenceMapper.toDomain(saved)).thenReturn(r1);

            List<StockReservation> result = adapter.createBatch(List.of(r1));

            assertEquals(1, result.size());
            assertSame(r1, result.get(0));
        }
    }

    @Nested
    @DisplayName("markConfirmed")
    class MarkConfirmed {

        @Test
        @DisplayName("guards RESERVED -> CONFIRMED and returns only ids whose row was updated")
        void shouldReturnOnlyTransitionedIds() {
            when(springDataStockReservationRepository.transitionStatus(1L, "RESERVED", "CONFIRMED")).thenReturn(1);
            when(springDataStockReservationRepository.transitionStatus(2L, "RESERVED", "CONFIRMED")).thenReturn(0);

            Set<StockReservationId> result = adapter.markConfirmed(
                    List.of(new StockReservationId(1L), new StockReservationId(2L)));

            assertEquals(Set.of(new StockReservationId(1L)), result);
            verify(springDataStockReservationRepository, never()).saveAllAndFlush(any());
            verify(springDataStockReservationRepository, never()).findAllById(any());
        }

        @Test
        @DisplayName("returns empty set without querying when input is empty")
        void shouldNotQueryWhenEmpty() {
            Set<StockReservationId> result = adapter.markConfirmed(List.of());

            assertTrue(result.isEmpty());
            verifyNoInteractions(springDataStockReservationRepository);
        }
    }

    @Nested
    @DisplayName("markExpired")
    class MarkExpired {

        @Test
        @DisplayName("guards RESERVED -> EXPIRED, deduplicates ids and updates in ascending id order")
        void shouldUpdateDistinctIdsInAscendingOrder() {
            when(springDataStockReservationRepository.transitionStatus(anyLong(), eq("RESERVED"), eq("EXPIRED")))
                    .thenReturn(1);

            Set<StockReservationId> result = adapter.markExpired(List.of(
                    new StockReservationId(3L), new StockReservationId(1L), new StockReservationId(3L)));

            assertEquals(Set.of(new StockReservationId(1L), new StockReservationId(3L)), result);
            InOrder inOrder = inOrder(springDataStockReservationRepository);
            inOrder.verify(springDataStockReservationRepository).transitionStatus(1L, "RESERVED", "EXPIRED");
            inOrder.verify(springDataStockReservationRepository).transitionStatus(3L, "RESERVED", "EXPIRED");
            verify(springDataStockReservationRepository, times(2)).transitionStatus(anyLong(), any(), any());
        }

        @Test
        @DisplayName("returns empty set when every row was already handled concurrently")
        void shouldReturnEmptyWhenNoneTransitioned() {
            when(springDataStockReservationRepository.transitionStatus(1L, "RESERVED", "EXPIRED")).thenReturn(0);

            Set<StockReservationId> result = adapter.markExpired(List.of(new StockReservationId(1L)));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("returns mapped domain when entity found")
        void shouldReturnWhenFound() {
            StockReservation reservation = buildReservation(1L);
            StockReservationEntity entity = buildEntity(1L);
            StockReservationId id = new StockReservationId(1L);

            when(springDataStockReservationRepository.findById(1L)).thenReturn(Optional.of(entity));
            when(stockReservationPersistenceMapper.toDomain(entity)).thenReturn(reservation);

            Optional<StockReservation> result = adapter.getById(id);

            assertTrue(result.isPresent());
            assertSame(reservation, result.get());
        }

        @Test
        @DisplayName("returns empty when entity not found")
        void shouldReturnEmptyWhenNotFound() {
            StockReservationId id = new StockReservationId(99L);

            when(springDataStockReservationRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<StockReservation> result = adapter.getById(id);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getExpiredReservations")
    class GetExpiredReservations {

        @Test
        @DisplayName("passes expiry bound, cursor position and limit to the keyset query, returns mapped domain objects")
        void shouldReturnExpiredReservations() {
            Instant now = Instant.now();
            Instant cursorExpiresAt = now.minusSeconds(300);
            ExpiryCursor cursor = new ExpiryCursor(cursorExpiresAt, new StockReservationId(42L));
            StockReservationEntity entity = buildEntity(43L);
            StockReservation reservation = buildReservation(43L);

            when(springDataStockReservationRepository.findExpiredAfter(now, cursorExpiresAt, 42L, 500))
                    .thenReturn(List.of(entity));
            when(stockReservationPersistenceMapper.toDomain(entity)).thenReturn(reservation);

            List<StockReservation> result = adapter.getExpiredReservations(now, cursor, 500);

            assertEquals(List.of(reservation), result);
            verify(springDataStockReservationRepository).findExpiredAfter(now, cursorExpiresAt, 42L, 500);
        }

        @Test
        @DisplayName("starts from the epoch and id 0 for the first page")
        void shouldQueryFromStartCursor() {
            Instant now = Instant.now();
            when(springDataStockReservationRepository.findExpiredAfter(now, Instant.EPOCH, 0L, 500))
                    .thenReturn(List.of());

            List<StockReservation> result = adapter.getExpiredReservations(now, ExpiryCursor.start(), 500);

            assertTrue(result.isEmpty());
            verify(stockReservationPersistenceMapper, never()).toDomain(any());
        }
    }

    @Nested
    @DisplayName("getLineViews")
    class GetLineViews {

        @Test
        @DisplayName("queries by merchant, store and order and maps the projection (micros -> Instant)")
        void shouldMapLineViews() {
            UUID orderItemUUID = UUID.randomUUID();
            UUID productUUID = UUID.randomUUID();
            SpringDataStockReservationRepository.LineView view = mock(SpringDataStockReservationRepository.LineView.class);
            when(view.getId()).thenReturn(7L);
            when(view.getOrderItemId()).thenReturn(orderItemUUID);
            when(view.getInventoryId()).thenReturn(inventoryId.getValue());
            when(view.getProductId()).thenReturn(productUUID);
            when(view.getSku()).thenReturn("SKU-1");
            when(view.getQuantity()).thenReturn(3L);
            when(view.getStatus()).thenReturn("RESERVED");
            when(view.getExpiresAtMicros()).thenReturn(1_762_509_600_123_456L);
            when(springDataStockReservationRepository.findLineViews(merchantId.getValue(), storeId.getValue(),
                    orderId.getValue())).thenReturn(List.of(view));

            List<ReservationLineView> result = adapter.getLineViews(merchantId, storeId, orderId);

            assertEquals(1, result.size());
            ReservationLineView line = result.get(0);
            assertEquals(StockReservationId.of(7L), line.id());
            assertEquals(OrderItemId.of(orderItemUUID), line.orderItemId());
            assertEquals(inventoryId, line.inventoryId());
            assertEquals(ProductId.of(productUUID), line.productId());
            assertEquals(SKU.of("SKU-1"), line.sku());
            assertEquals(3L, line.quantity());
            assertEquals(ReservationStatus.RESERVED, line.status());
            assertEquals(Instant.parse("2025-11-07T10:00:00.123456Z"), line.expiresAt());
        }
    }

    @Nested
    @DisplayName("markReleased / markReturned")
    class MarkReleasedReturned {

        @Test
        @DisplayName("markReleased guards RESERVED -> RELEASED")
        void shouldGuardRelease() {
            when(springDataStockReservationRepository.transitionStatus(1L, "RESERVED", "RELEASED")).thenReturn(1);
            when(springDataStockReservationRepository.transitionStatus(2L, "RESERVED", "RELEASED")).thenReturn(0);

            Set<StockReservationId> result = adapter.markReleased(
                    List.of(new StockReservationId(1L), new StockReservationId(2L)));

            assertEquals(Set.of(new StockReservationId(1L)), result);
        }

        @Test
        @DisplayName("markReturned guards CONFIRMED -> RETURNED")
        void shouldGuardReturn() {
            when(springDataStockReservationRepository.transitionStatus(3L, "CONFIRMED", "RETURNED")).thenReturn(1);

            Set<StockReservationId> result = adapter.markReturned(List.of(new StockReservationId(3L)));

            assertEquals(Set.of(new StockReservationId(3L)), result);
        }
    }
}
