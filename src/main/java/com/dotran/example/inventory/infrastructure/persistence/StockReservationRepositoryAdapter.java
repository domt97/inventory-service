package com.dotran.example.inventory.infrastructure.persistence;

import com.dotran.example.inventory.application.repository.StockReservationRepository;
import com.dotran.example.inventory.common.annotation.PersistenceAdapter;
import com.dotran.example.inventory.domain.model.StockReservation;
import com.dotran.example.inventory.infrastructure.mapper.StockReservationPersistenceMapper;
import com.dotran.example.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.example.inventory.infrastructure.persistence.jpa.SpringDataStockReservationRepository;
import lombok.RequiredArgsConstructor;

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
}
