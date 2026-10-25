package com.dotran.oms.inventory.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.core.cloud.dynamodb.DynamoDbMerchantInfoRepository;
import com.dotran.oms.core.domain.MerchantInfo;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.inventory.application.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@PersistenceAdapter
@RequiredArgsConstructor
public class MerchantRepositoryAdapter implements MerchantRepository {

    private final DynamoDbMerchantInfoRepository dynamoDbMerchantInfoRepository;

    @Override
    public Optional<MerchantInfo> findByMerchantId(MerchantId merchantId) {
        return dynamoDbMerchantInfoRepository.findByMerchantId(merchantId);
    }
}
