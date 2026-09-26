package com.dotran.oms.inventory.infrastructure.swagger;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerGroupConfig {

    @Bean
    public GroupedOpenApi inventoryApi() {
        return GroupedOpenApi.builder()
                .group("inventory")
                .packagesToScan(
                        "com.dotran.oms.inventory.infrastructure.rest"
                )
                .build();
    }
}
