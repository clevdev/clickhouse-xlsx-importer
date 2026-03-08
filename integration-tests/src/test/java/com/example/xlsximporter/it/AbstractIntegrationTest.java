package com.example.xlsximporter.it;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all integration tests in this module.
 *
 * <h3>Containers started (static — once per JVM session)</h3>
 * <ul>
 *   <li>PostgreSQL 16 → replaces {@code spring.datasource.*}</li>
 *   <li>ClickHouse Node 1 → replaces {@code clickhouse.node1.*}</li>
 *   <li>ClickHouse Node 2 → replaces {@code clickhouse.node2.*}</li>
 * </ul>
 *
 * <h3>Engine mode</h3>
 * Single-node Testcontainers ClickHouse instances have no ZooKeeper, so
 * {@code ReplicatedMergeTree} is not available. The property
 * {@code clickhouse.use-plain-merge-tree-in-tests=true} switches
 * {@link com.example.xlsximporter.service.ClickHouseScriptBuilder} to emit
 * {@code MergeTree()} instead. This is set via {@link DynamicPropertySource}.
 */
@Testcontainers
@ActiveProfiles("it")
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("importdb_it")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Container
    static final ClickHouseContainer CLICKHOUSE_1 =
            new ClickHouseContainer("clickhouse/clickhouse-server:24.3");

    @Container
    static final ClickHouseContainer CLICKHOUSE_2 =
            new ClickHouseContainer("clickhouse/clickhouse-server:24.3");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        // PostgreSQL
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",  POSTGRES::getUsername);
        r.add("spring.datasource.password",  POSTGRES::getPassword);

        // ClickHouse Node 1
        r.add("clickhouse.node1.url", () ->
                "jdbc:ch://" + CLICKHOUSE_1.getHost()
                + ":" + CLICKHOUSE_1.getMappedPort(8123) + "/default");
        r.add("clickhouse.node1.username", () -> "default");
        r.add("clickhouse.node1.password", () -> "");

        // ClickHouse Node 2
        r.add("clickhouse.node2.url", () ->
                "jdbc:ch://" + CLICKHOUSE_2.getHost()
                + ":" + CLICKHOUSE_2.getMappedPort(8123) + "/default");
        r.add("clickhouse.node2.username", () -> "default");
        r.add("clickhouse.node2.password", () -> "");

        // Use plain MergeTree() — no ZooKeeper in single-node Testcontainers
        r.add("clickhouse.use-plain-merge-tree-in-tests", () -> "true");
    }
}
