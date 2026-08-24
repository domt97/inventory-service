package com.dotran.example.inventory.common.domain.valueobject;

import lombok.Getter;

@Getter
public class ProductSku extends BaseId<String> {

    public ProductSku(String value) {
        super(value);
    }

    public static ProductSku of(String value) {
        return new ProductSku(value);
    }
}
