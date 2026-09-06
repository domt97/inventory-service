package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.domain.model.StockReservation;
import com.dotran.example.inventory.infrastructure.mapper.StockReservationPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataStockReservationRepository;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@PersistenceAdapter
@RequiredArgsConstructor
public class StockReservationRepositoryAdapter implements StockReservationRepository {

    private final SpringDataStockReservationRepository springDataStockReservationRepository;
    private final StockReservationPersistenceMapper stockReservationPersistenceMapper;

    @Override
    public StockReservation create(StockReservation stockReservation) {
        StockReservationEntity entity = stockReservationPersistenceMapper.toEntity(stockReservation);

        StockReservationEntity savedEntity = springDataStockReservationRepository.saveAndFlush(entity);

        return stockReservationPersistenceMapper.toDomain(savedEntity);
    }

    @Override
    public List<StockReservation> getByOrderId(OrderId orderId) {
        List<StockReservationEntity> entities = springDataStockReservationRepository.findByOrderId(orderId.getValue());

        return entities.stream()
                .map(stockReservationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<StockReservation> confirm(List<StockReservation> stockReservations) {
        Map<Long, StockReservation> stockReservationMap = stockReservations
                .stream()
                .collect(Collectors.toMap(e -> e.getId().getValue(), Function.identity()));

        List<StockReservationEntity> entities = springDataStockReservationRepository
                .findAllById(stockReservationMap.keySet());
        Set<Long> foundIds = entities.stream()
                .map(StockReservationEntity::getId)
                .collect(Collectors.toSet());
        if (!foundIds.equals(stockReservationMap.keySet())) {
            Set<Long> missingIds = new HashSet<>(stockReservationMap.keySet());

            missingIds.removeAll(foundIds);

            throw new IllegalStateException("Stock reservations not found: " + missingIds);
        }

        for (StockReservationEntity entity : entities) {
            StockReservation stockReservation = stockReservationMap.get(entity.getId());

            if (stockReservation != null) {
                entity.setStatus(stockReservation.getStatus());
                entity.setUpdatedAt(stockReservation.getUpdatedAt());
            }
        }

        List<StockReservationEntity> updatedEntities = springDataStockReservationRepository.saveAllAndFlush(entities);

        return updatedEntities.stream()
                .map(stockReservationPersistenceMapper::toDomain)
                .toList();
    }
}
