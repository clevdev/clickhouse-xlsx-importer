package com.example.xlsximporter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Entry point for the XLSX → ClickHouse Importer.
 *
 * DataSourceAutoConfiguration is excluded because we register ALL three
 * DataSource beans manually in DataSourceConfig (PostgreSQL + 2x ClickHouse).
 * Without exclusion, Boot would try to create a 4th DataSource from
 * spring.datasource.* and cause "required a single bean, but N were found".
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class XlsxImporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(XlsxImporterApplication.class, args);
    }
}
