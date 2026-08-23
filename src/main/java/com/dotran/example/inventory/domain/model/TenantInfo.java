package com.dotran.example.inventory.domain.model;

import com.dotran.example.inventory.common.domain.AggregateRoot;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class TenantInfo extends AggregateRoot<TenantId> {

    private String code;
    private String name;
    private String status;
    private TenantSetting settings;
    private Instant createdAt;
    private Instant updatedAt;
}
