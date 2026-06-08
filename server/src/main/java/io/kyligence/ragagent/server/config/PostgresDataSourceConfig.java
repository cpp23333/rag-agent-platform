package io.kyligence.ragagent.server.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PostgresDataSourceConfig {

    @Bean("pgDataSource")
    @ConfigurationProperties("spring.datasource.postgres")
    public DataSource pgDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
