package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.infrastructure.mapper.StockReservationPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationRepository;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
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

    private TenantId tenantId;
    private StoreId storeId;
    private InventoryId inventoryId;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
        inventoryId = InventoryId.of(UUID.randomUUID());
        orderId = OrderId.of(UUID.randomUUID());
    }

    private StockReservation buildReservation(long id) {
        return StockReservation.builder()
                .id(new StockReservationId(id))
                .tenantId(tenantId)
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
        @DisplayName("queries by RESERVED status and expiry timestamp, returns mapped domain objects")
        void shouldReturnExpiredReservations() {
            Instant now = Instant.now();
            StockReservationEntity entity = buildEntity(1L);
            StockReservation reservation = buildReservation(1L);

            when(springDataStockReservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now))
                    .thenReturn(List.of(entity));
            when(stockReservationPersistenceMapper.toDomain(entity)).thenReturn(reservation);

            List<StockReservation> result = adapter.getExpiredReservations(now);

            assertEquals(1, result.size());
            verify(springDataStockReservationRepository).findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now);
        }

        @Test
        @DisplayName("returns empty list when no expired reservations found")
        void shouldReturnEmptyWhenNoneExpired() {
            Instant now = Instant.now();
            when(springDataStockReservationRepository.findByStatusAndExpiresAtBefore(any(), any()))
                    .thenReturn(List.of());

            List<StockReservation> result = adapter.getExpiredReservations(now);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getByOrderId")
    class GetByOrderId {

        @Test
        @DisplayName("queries by order UUID and returns mapped domain objects")
        void shouldReturnReservationsByOrder() {
            UUID orderUUID = orderId.getValue();
            StockReservationEntity entity = buildEntity(1L);
            StockReservation reservation = buildReservation(1L);

            when(springDataStockReservationRepository.findByOrderId(orderUUID)).thenReturn(List.of(entity));
            when(stockReservationPersistenceMapper.toDomain(entity)).thenReturn(reservation);

            List<StockReservation> result = adapter.getByOrderId(orderId);

            assertEquals(1, result.size());
            verify(springDataStockReservationRepository).findByOrderId(orderUUID);
        }
    }
}
