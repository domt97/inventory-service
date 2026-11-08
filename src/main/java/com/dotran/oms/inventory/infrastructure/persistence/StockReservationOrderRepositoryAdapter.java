package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.SKU;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.inventory.application.repository.StockReservationOrderRepository;
import com.dotran.oms.inventory.domain.enums.LineRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderRejectionReason;
import com.dotran.oms.inventory.domain.enums.OrderReservationStatus;
import com.dotran.oms.inventory.domain.model.ReservationRejection;
import com.dotran.oms.inventory.domain.model.StockReservationOrder;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationOrderRepository;
import com.dotran.oms.inventory.infrastructure.persistence.jpa.SpringDataStockReservationOrderRepository.HeaderView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@PersistenceAdapter
@RequiredArgsConstructor
public class StockReservationOrderRepositoryAdapter implements StockReservationOrderRepository {

    private final SpringDataStockReservationOrderRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(StockReservationOrder header) {
        if (header.rejection() != null) {
            throw new IllegalArgumentException("A new order header has no rejection");
        }
        return repository.insertIfAbsent(header.orderId().getValue(), header.merchantId().getValue(),
                header.storeId().getValue(), header.status().name(), header.restock()) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StockReservationOrder> lockForUpdate(OrderId orderId, MerchantId merchantId, StoreId storeId) {
        return repository.lockForUpdate(orderId.getValue(), merchantId.getValue(), storeId.getValue())
                .map(this::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int update(StockReservationOrder header, OrderReservationStatus expectedStatus) {
        UUID orderId = header.orderId().getValue();
        UUID merchantId = header.merchantId().getValue();
        UUID storeId = header.storeId().getValue();
        if (header.rejection() == null) {
            return repository.update(orderId, merchantId, storeId, header.status().name(), header.restock(),
                    expectedStatus.name());
        }
        return repository.updateWithRejection(orderId, merchantId, storeId, header.status().name(), header.restock(),
                write(header.rejection()), expectedStatus.name());
    }

    private StockReservationOrder toDomain(HeaderView view) {
        return new StockReservationOrder(OrderId.of(view.getOrderId()), MerchantId.of(view.getMerchantId()),
                StoreId.of(view.getStoreId()), OrderReservationStatus.valueOf(view.getStatus()), view.getRestock(),
                view.getRejection() == null ? null : read(view.getRejection()));
    }

    private String write(ReservationRejection rejection) {
        RejectionJson json = new RejectionJson(
                rejection.orderReason() == null ? null : rejection.orderReason().name(),
                rejection.lines().stream()
                        .map(line -> new LineJson(line.orderItemId().getValue(), line.sku().getValue(),
                                line.reason().name()))
                        .toList());
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the reservation rejection", e);
        }
    }

    private ReservationRejection read(String text) {
        RejectionJson json;
        try {
            json = objectMapper.readValue(text, RejectionJson.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored reservation rejection is not readable", e);
        }
        List<ReservationRejection.Line> lines = json.lines() == null ? List.of() : json.lines().stream()
                .map(line -> new ReservationRejection.Line(OrderItemId.of(line.orderItemId()), SKU.of(line.sku()),
                        LineRejectionReason.valueOf(line.reason())))
                .toList();
        return new ReservationRejection(
                json.orderReason() == null ? null : OrderRejectionReason.valueOf(json.orderReason()), lines);
    }

    /** Stored JSON shape of {@code stock_reservation_orders.rejection}; independent of the domain record. */
    record RejectionJson(String orderReason, List<LineJson> lines) {
    }

    record LineJson(UUID orderItemId, String sku, String reason) {
    }
}
