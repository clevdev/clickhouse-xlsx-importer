package com.example.xlsximporter.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configures three independent HikariCP datasources:
 * <ol>
 *   <li><b>PostgreSQL</b> — {@code @Primary}, used by JPA for import logs.</li>
 *   <li><b>ClickHouse Node 1</b> — {@code jdbcTemplateNode1}.</li>
 *   <li><b>ClickHouse Node 2</b> — {@code jdbcTemplateNode2}.</li>
 * </ol>
 *
 * PostgreSQL DataSource is created EXPLICITLY here (not via Boot autoconfiguration)
 * so it is visible as a named @Primary bean and wins over the two ClickHouse DataSources
 * when Spring resolves the entityManagerFactory parameter.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {

    private final ClickHouseProperties chProps;

    /**
     * Binds spring.datasource.* into a DataSourceProperties object.
     * Needed to create the PostgreSQL DataSource explicitly.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties postgresDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Primary PostgreSQL DataSource — explicitly created so Spring can
     * unambiguously resolve it as @Primary when multiple DataSource beans exist.
     */
    @Bean("postgresDataSource")
    @Primary
    public DataSource postgresDataSource(
            @Qualifier("postgresDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean("entityManagerFactory")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            @Qualifier("postgresDataSource") DataSource dataSource) {

        var em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.example.xlsximporter.model");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.hbm2ddl.auto", "update");
        jpaProps.put("hibernate.dialect",      "org.hibernate.dialect.PostgreSQLDialect");
        jpaProps.put("hibernate.show_sql",     "false");
        em.setJpaPropertyMap(jpaProps);
        return em;
    }

    @Primary
    @Bean("transactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("entityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf) {
        var tx = new JpaTransactionManager();
        tx.setEntityManagerFactory(emf.getObject());
        return tx;
    }


    @Bean("dataSourceNode1")
    public DataSource dataSourceNode1() {
        return buildClickHousePool(chProps.getNode1());
    }

    @Bean("jdbcTemplateNode1")
    public JdbcTemplate jdbcTemplateNode1(
            @Qualifier("dataSourceNode1") DataSource ds) {
        return new JdbcTemplate(ds);
    }


    @Bean("dataSourceNode2")
    public DataSource dataSourceNode2() {
        return buildClickHousePool(chProps.getNode2());
    }

    @Bean("jdbcTemplateNode2")
    public JdbcTemplate jdbcTemplateNode2(
            @Qualifier("dataSourceNode2") DataSource ds) {
        return new JdbcTemplate(ds);
    }


    private HikariDataSource buildClickHousePool(ClickHouseProperties.NodeConfig node) {
        var pool = node.getPool();
        var cfg  = new HikariConfig();
        cfg.setJdbcUrl(node.getUrl());
        cfg.setUsername(node.getUsername());
        cfg.setPassword(node.getPassword());
        cfg.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        cfg.setPoolName(pool.getPoolName());
        cfg.setMaximumPoolSize(pool.getMaximumPoolSize());
        cfg.setMinimumIdle(pool.getMinimumIdle());
        cfg.setConnectionTimeout(pool.getConnectionTimeout());
        cfg.setIdleTimeout(pool.getIdleTimeout());
        cfg.setMaxLifetime(pool.getMaxLifetime());
        cfg.setConnectionTestQuery(pool.getConnectionTestQuery());
        log.info("HikariCP [{}] → {}", pool.getPoolName(), node.getUrl());
        return new HikariDataSource(cfg);
    }
}
