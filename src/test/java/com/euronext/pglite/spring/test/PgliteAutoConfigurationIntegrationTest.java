package com.euronext.pglite.spring.test;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PgliteAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PgliteAutoConfiguration.class))
            .withUserConfiguration(LiquibaseTestConfiguration.class)
            .withPropertyValues(
                    "pglite.enabled=true",
                    "pglite.database=postgres",
                    "pglite.jdbc-params=sslmode=disable&preferQueryMode=simple"
            );

    @Test
    void autoConfigurationExposesDataSourceAndLiquibaseRuns() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isNotNull();

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sample_people", Integer.class);
            assertThat(count).isEqualTo(2);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class LiquibaseTestConfiguration {

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean(initMethod = "afterPropertiesSet")
        LiquibaseRunner liquibaseRunner(DataSource dataSource) {
            return new LiquibaseRunner(dataSource);
        }
    }

    static class LiquibaseRunner {
        private final DataSource dataSource;

        LiquibaseRunner(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        public void afterPropertiesSet() throws Exception {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:/db/changelog/test-changelog.xml");
            liquibase.afterPropertiesSet();
        }
    }
}
