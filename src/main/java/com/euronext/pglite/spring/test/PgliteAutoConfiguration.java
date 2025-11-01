package com.euronext.pglite.spring.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

@AutoConfiguration
@EnableConfigurationProperties(PgliteProperties.class)
@ConditionalOnProperty(value = "pglite.enabled", havingValue = "true")
public class PgliteAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PgliteAutoConfiguration.class);

    @Bean(initMethod = "start", destroyMethod = "close")
    PgliteServerProcess pgliteServerProcess(PgliteProperties props) {
        return new PgliteServerProcess(
                props.getHost(), props.getPort(), props.getStartupTimeout(), props.getNodeCommand(),
                props.getPathPrepend(), props.getRuntimeDownloadUrlTemplate(), props.getRuntimeCacheDir(),
                props.getUsername(), props.getPassword()
        );
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    DataSource dataSource(PgliteServerProcess server, PgliteProperties props) {
        SingleConnectionDataSource single = new SingleConnectionDataSource();
        single.setSuppressClose(true);
        single.setAutoCommit(false);
        single.setDriverClassName("org.postgresql.Driver");

        String url = server.jdbcUrl(props.getDatabase(), props.getJdbcParams());
        single.setUrl(url);
        single.setUsername(props.getUsername());
        single.setPassword(props.getPassword());

        log.info("PGlite DataSource ready: {} (single physical connection)", url);
        return new TransactionAwareDataSourceProxy(single);
    }
}

