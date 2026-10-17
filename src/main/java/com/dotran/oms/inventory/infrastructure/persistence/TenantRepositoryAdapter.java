package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.cloud.dynamodb.DynamoDbTenantInfoRepository;
import com.dotran.oms.core.domain.TenantInfo;
import com.dotran.oms.core.domain.id.TenantId;
import com.dotran.oms.inventory.application.repository.TenantRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@PersistenceAdapter
@RequiredArgsConstructor
public class TenantRepositoryAdapter implements TenantRepository {

    private final DynamoDbTenantInfoRepository dynamoDbTenantInfoRepository;

    @Override
    public Optional<TenantInfo> findByTenantId(TenantId tenantId) {
        return dynamoDbTenantInfoRepository.findByTenantId(tenantId);
    }
}
