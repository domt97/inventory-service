package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.common.id.StockMovementId;
import com.dotran.oms.inventory.domain.enums.StockMovementType;
import com.dotran.oms.inventory.domain.model.StockMovement;
import com.dotran.oms.inventory.infrastructure.mapper.StockMovementPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockMovementEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockMovementRepository;
import com.dotran.oms.core.domain.id.InventoryId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.core.domain.id.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockMovementRepositoryAdapter")
class StockMovementRepositoryAdapterTest {

    @Mock
    private SpringDataStockMovementRepository repository;

    @Mock
    private StockMovementPersistenceMapper mapper;

    @InjectMocks
    private StockMovementRepositoryAdapter adapter;

    private UUID inventoryUUID;
    private InventoryId inventoryId;
    private TenantId tenantId;
    private StoreId storeId;

    @BeforeEach
    void setUp() {
        inventoryUUID = UUID.randomUUID();
        inventoryId = InventoryId.of(inventoryUUID);
        tenantId = TenantId.of(UUID.randomUUID());
        storeId = StoreId.of(UUID.randomUUID());
    }

    private StockMovement buildMovement() {
        return StockMovement.builder()
                .id(new StockMovementId(1L))
                .tenantId(tenantId)
                .storeId(storeId)
                .inventoryId(inventoryId)
                .type(StockMovementType.ADJUSTMENT_IN)
                .quantity(10L)
                .build();
    }

    private StockMovementEntity buildEntity(long id) {
        StockMovementEntity entity = new StockMovementEntity();
        entity.setId(id);
        return entity;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("maps domain to entity, saves, and returns mapped domain")
        void shouldCreateAndReturnMappedMovement() {
            StockMovement movement = buildMovement();
            StockMovementEntity entity = buildEntity(1L);
            StockMovementEntity saved = buildEntity(1L);

            when(mapper.fromStockMovement(movement)).thenReturn(entity);
            when(repository.saveAndFlush(entity)).thenReturn(saved);
            when(mapper.fromEntity(saved)).thenReturn(movement);

            StockMovement result = adapter.create(movement);

            assertSame(movement, result);
            verify(repository).saveAndFlush(entity);
        }
    }

    @Nested
    @DisplayName("getByInventoryId")
    class GetByInventoryId {

        @Test
        @DisplayName("unwraps inventoryId UUID, queries, and returns mapped domain objects")
        void shouldReturnMovementsByInventoryId() {
            StockMovement movement = buildMovement();
            StockMovementEntity entity = buildEntity(1L);

            when(repository.findAllByInventoryId(inventoryUUID)).thenReturn(List.of(entity));
            when(mapper.fromEntity(entity)).thenReturn(movement);

            List<StockMovement> result = adapter.getByInventoryId(inventoryId);

            assertEquals(1, result.size());
            assertSame(movement, result.get(0));
            verify(repository).findAllByInventoryId(inventoryUUID);
        }

        @Test
        @DisplayName("returns empty list when no movements found")
        void shouldReturnEmptyWhenNoMovements() {
            when(repository.findAllByInventoryId(inventoryUUID)).thenReturn(List.of());

            List<StockMovement> result = adapter.getByInventoryId(inventoryId);

            assertTrue(result.isEmpty());
        }
    }
}
