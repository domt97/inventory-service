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

/** Last applied catalog event version per product; written only through the native upsert. */
@Entity
@Table(name = "product_catalog_versions")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor
public class ProductCatalogVersionEntity {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
