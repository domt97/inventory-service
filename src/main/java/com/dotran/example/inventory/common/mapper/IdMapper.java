package com.dotran.example.inventory.common.mapper;

import com.dotran.example.inventory.common.domain.valueobject.CategoryId;
import com.dotran.example.inventory.common.domain.valueobject.CustomerId;
import com.dotran.example.inventory.common.domain.valueobject.InventoryId;
import com.dotran.example.inventory.common.domain.valueobject.OrderId;
import com.dotran.example.inventory.common.domain.valueobject.OrderItemId;
import com.dotran.example.inventory.common.domain.valueobject.ProductId;
import com.dotran.example.inventory.common.domain.valueobject.ProductImageId;
import com.dotran.example.inventory.common.domain.valueobject.ReferenceId;
import com.dotran.example.inventory.common.domain.valueobject.SKU;
import com.dotran.example.inventory.common.domain.valueobject.StockMovementId;
import com.dotran.example.inventory.common.domain.valueobject.StockReservationId;
import com.dotran.example.inventory.common.domain.valueobject.StoreAvailabilityId;
import com.dotran.example.inventory.common.domain.valueobject.StoreCollectionId;
import com.dotran.example.inventory.common.domain.valueobject.StoreId;
import com.dotran.example.inventory.common.domain.valueobject.TenantId;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface IdMapper {

    @Named("toStoreId")
    default StoreId toStoreId(UUID id) {
        return id == null ? null : new StoreId(id);
    }

    @Named("toStoreAvailabilityId")
    default StoreAvailabilityId toStoreAvailabilityId(UUID id) {
        return id == null ? null : new StoreAvailabilityId(id);
    }

    @Named("toSKU")
    default SKU toSKU(String id) {
        return id == null ? null : new SKU(id);
    }

    @Named("toSKUList")
    default List<SKU> toSKUList(List<String> skus) {
        return skus == null ? null : skus.stream().map(this::toSKU).toList();
    }

    @Named("toProductImageId")
    default ProductImageId toProductImageId(Long id) {
        return id == null ? null : new ProductImageId(id);
    }

    @Named("toProductId")
    default ProductId toProductId(UUID id) {
        return id == null ? null : new ProductId(id);
    }

    @Named("toCategoryId")
    default CategoryId toCategoryId(UUID id) {
        return id == null ? null : new CategoryId(id);
    }

    @Named("toStoreCollectionId")
    default StoreCollectionId toStoreCollectionId(UUID id) {
        return id == null ? null : new StoreCollectionId(id);
    }

    @Named("toTenantId")
    default TenantId toTenantId(UUID id) {
        return id == null ? null : new TenantId(id);
    }

    @Named("toCustomerId")
    default CustomerId toCustomerId(UUID id) {
        return id == null ? null : new CustomerId(id);
    }

    @Named("toInventoryId")
    default InventoryId toInventoryId(UUID id) {
        return id == null ? null : new InventoryId(id);
    }

    @Named("toStockMovementId")
    default StockMovementId toStockMovementId(Long id) {
        return id == null ? null : new StockMovementId(id);
    }

    @Named("toReferenceId")
    default ReferenceId toReferenceId(UUID id) {
        return id == null ? null : new ReferenceId(id);
    }

    @Named("toStockReservationId")
    default StockReservationId toStockReservationId(Long id) {
        return id == null ? null : new StockReservationId(id);
    }

    @Named("toOrderId")
    default OrderId toOrderId(UUID id) {
        return id == null ? null : new OrderId(id);
    }

    @Named("toOrderItemId")
    default OrderItemId toOrderItemId(UUID id) {
        return id == null ? null : new OrderItemId(id);
    }
}
