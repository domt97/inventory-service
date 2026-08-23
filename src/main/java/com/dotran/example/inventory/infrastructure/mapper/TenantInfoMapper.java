package com.dotran.example.inventory.infrastructure.mapper;

import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import com.dotran.example.inventory.domain.model.TenantInfo;
import com.dotran.example.inventory.domain.model.TenantSetting;
import com.dotran.example.inventory.infrastructure.cloud.dynamodb.TenantInfoItem;
import com.dotran.example.inventory.infrastructure.cloud.dynamodb.TenantSettingsItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TenantInfoMapper {

    @Mapping(target = "id", expression = "java(com.dotran.example.inventory.common.domain.valueobject.TenantId.of(item.getPk()))")
    @Mapping(target = "settings", source = "settings")
    TenantInfo fromItemToTenantInfo(TenantInfoItem item);

    @Mapping(target = "pk", source = "id")
    TenantInfoItem fromTenantInfoToItem(TenantInfo tenantInfo);

    TenantSetting fromItemToSettings(TenantSettingsItem item);

    TenantSettingsItem fromSettingsToItem(TenantSetting settings);

    default TenantInfo mapTenantInfoItem(TenantInfoItem item) {
        return item == null ? null : fromItemToTenantInfo(item);
    }

    default java.util.UUID mapTenantIdToPk(TenantId tenantId) {
        return tenantId != null ? tenantId.getValue() : null;
    }
}
