package com.dotran.oms.inventory.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Order header of the stock commands (ADR 0006). Read and written only through the native queries of
 * {@code SpringDataStockReservationOrderRepository}; mapped so Spring Data has a repository for them.
 */
@Entity
@Table(name = "stock_reservation_orders")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor
public class StockReservationOrderEntity {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column
    private Boolean restock;

    /** JSONB, kept as text; (de)serialized by the adapter. */
    @Column(columnDefinition = "jsonb", insertable = false, updatable = false)
    private String rejection;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
