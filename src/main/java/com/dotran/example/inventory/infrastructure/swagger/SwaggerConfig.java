package com.dotran.example.inventory.infrastructure.swagger;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI storeOpenAPI() {

        return new OpenAPI()
                .info(new Info()
                        .title("Inventory Service API")
                        .description("Inventory Management APIs")
                        .version("v1")
                        .contact(new Contact()
                                .name("Do Tran"))
                        .license(new License()
                                .name("Apache 2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8058")
                                .description("Local"),
                        new Server()
                                .url("https://api.example.com")
                                .description("Production")
                ))
                .externalDocs(new ExternalDocumentation()
                        .description("API Documentation"));
    }

    @Bean
    public GroupedOpenApi storeApi() {
        return GroupedOpenApi.builder()
                .group("store")
                .packagesToScan("com.dotran.example.inventory.infrastructure.rest")
                .build();
    }
}
