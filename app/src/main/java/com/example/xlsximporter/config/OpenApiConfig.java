package com.example.xlsximporter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger / OpenAPI configuration.
 *
 * <p>springdoc-openapi {@code 2.8.x} is required for Spring Boot 3.4+ / Spring MVC 6.2+.
 * Earlier 2.x versions relied on the removed {@code PathMatchingContentNegotiationStrategy}.
 * Access Swagger UI at {@code http://localhost:8080/swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String port;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("XLSX → ClickHouse Importer API")
                        .version("1.0.0")
                        .description("""
                                Upload `.xlsx` files to dynamically create ClickHouse tables and import data.

                                **xlsx format:**
                                | Row | Content |
                                |-----|---------|
                                | 1 | Column names |
                                | 2 | ClickHouse types (`String`, `Int64`, `Date`, `Nullable(Float64)`, …) |
                                | 3+ | Data rows |

                                **Auto-generated columns:**
                                - `<col>_str String` (or `Nullable(String)` if source is `Nullable`) for every Date/DateTime column
                                - `operation_dttm DateTime`

                                **Write strategy:** DDL executed on both nodes; INSERT on Node 1 only \
                                (ReplicatedMergeTree replicates to Node 2).
                                """)
                        .contact(new Contact().name("Dev Team").email("dev@example.com"))
                        .license(new License().name("Apache 2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port).description("Local")));
    }
}
