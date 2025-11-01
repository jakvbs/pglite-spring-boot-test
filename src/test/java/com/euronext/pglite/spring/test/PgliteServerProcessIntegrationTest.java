package com.euronext.pglite.spring.test;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PgliteServerProcessIntegrationTest {

    @Test
    void liquibaseMigrationsAndJdbcOperationsSucceed() throws Exception {
        try (PgliteServerProcess server = new PgliteServerProcess(
                "127.0.0.1",
                0,
                Duration.ofSeconds(30),
                null,
                null,
                null,
                null,
                null,
                null,
                PgliteProperties.LogLevel.WARNING
        )) {
            server.start();

            DataSource dataSource = createDataSource(server);
            runLiquibase(dataSource);
            runJdbcOperations(dataSource);
        }
    }

    private DataSource createDataSource(PgliteServerProcess server) {
        SingleConnectionDataSource single = new SingleConnectionDataSource();
        single.setSuppressClose(true);
        single.setAutoCommit(false);
        single.setDriverClassName("org.postgresql.Driver");
        single.setUrl(server.jdbcUrl("postgres", "sslmode=disable&preferQueryMode=simple"));
        single.setUsername("postgres");
        single.setPassword("");
        return new TransactionAwareDataSourceProxy(single);
    }

    private void runLiquibase(DataSource dataSource) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/test-changelog.xml");
        liquibase.afterPropertiesSet();
    }

    private void runJdbcOperations(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sample_people (person_id, full_name) VALUES (?, ?)")) {
                insert.setInt(1, 3);
                insert.setString(2, "Charlie");
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE sample_people SET full_name = ? WHERE person_id = ?")) {
                update.setString(1, "Bob Updated");
                update.setInt(2, 2);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM sample_people WHERE person_id = ?")) {
                delete.setInt(1, 1);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }

            connection.commit();
        }

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sample_people", Integer.class);
        assertThat(count).isEqualTo(2);

        String bobName = jdbcTemplate.queryForObject(
                "SELECT full_name FROM sample_people WHERE person_id = ?",
                new Object[]{2},
                String.class
        );
        assertThat(bobName).isEqualTo("Bob Updated");
    }
}
