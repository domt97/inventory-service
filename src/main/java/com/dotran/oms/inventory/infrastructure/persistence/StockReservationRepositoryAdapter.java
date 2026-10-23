package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.inventory.application.repository.StockReservationRepository;
import com.dotran.oms.inventory.common.id.StockReservationId;
import com.dotran.oms.inventory.domain.enums.ReservationStatus;
import com.dotran.oms.inventory.domain.model.StockReservation;
import com.dotran.oms.inventory.infrastructure.mapper.StockReservationPersistenceMapper;
import com.dotran.oms.inventory.infrastructure.persistence.entity.StockReservationEntity;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationRepository;
import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.OrderId;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    public List<StockReservation> createBatch(List<StockReservation> stockReservations) {
        List<StockReservationEntity> entities = stockReservations.stream()
                .map(stockReservationPersistenceMapper::toEntity)
                .toList();

        List<StockReservationEntity> savedEntities = springDataStockReservationRepository.saveAllAndFlush(entities);

        return savedEntities.stream()
                .map(stockReservationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<StockReservationId> markExpired(Collection<StockReservationId> stockReservationIds) {
        return transition(stockReservationIds, ReservationStatus.RESERVED, ReservationStatus.EXPIRED);
    }

    @Override
    public List<StockReservation> getByOrderId(OrderId orderId) {
        List<StockReservationEntity> entities = springDataStockReservationRepository.findByOrderId(orderId.getValue());

        return entities.stream()
                .map(stockReservationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<StockReservationId> markConfirmed(Collection<StockReservationId> stockReservationIds) {
        return transition(stockReservationIds, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED);
    }

    @Override
    public List<StockReservation> getExpiredReservations(Instant expiresAtBefore) {
        List<StockReservationEntity> entities = springDataStockReservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, expiresAtBefore);

        return entities.stream()
                .map(stockReservationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<StockReservation> getById(StockReservationId stockReservationId) {
        return springDataStockReservationRepository.findById(stockReservationId.getValue())
                .map(stockReservationPersistenceMapper::toDomain);
    }

    /**
     * Ids are deduplicated and updated in ascending order so concurrent transactions lock rows in the same order.
     * A row that is missing or no longer in {@code from} updates 0 rows and is reported as not transitioned
     * (reservations are never deleted, so in practice this means another transaction already moved it).
     * The native UPDATE bypasses the persistence context: entities already loaded in the same transaction keep
     * their old status, so do not re-read reservations after this call without clearing the context.
     */
    private Set<StockReservationId> transition(Collection<StockReservationId> stockReservationIds,
                                               ReservationStatus from,
                                               ReservationStatus to) {
        List<Long> ids = stockReservationIds.stream()
                .map(StockReservationId::getValue)
                .distinct()
                .sorted()
                .toList();

        Set<StockReservationId> transitioned = new LinkedHashSet<>();
        for (Long id : ids) {
            int updated = springDataStockReservationRepository.transitionStatus(id, from.name(), to.name());
            if (updated == 1) {
                transitioned.add(StockReservationId.of(id));
            }
        }
        return transitioned;
    }
}
