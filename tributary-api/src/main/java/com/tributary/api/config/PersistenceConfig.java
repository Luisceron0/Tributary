package com.tributary.api.config;

import com.tributary.persistence.DataSourceFactory;
import com.tributary.persistence.FlywayMigrator;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the SAME {@link DataSourceFactory}/{@link FlywayMigrator} every persistence test in this
 * project already uses, rather than pulling in Spring Boot's own {@code
 * spring-boot-starter-jdbc} autoconfiguration for a second, parallel way to build a {@link
 * DataSource} — one mechanism, already proven, not two.
 */
@Configuration
public class PersistenceConfig {

  @Bean
  public DataSource dataSource(
      @Value("${tributary.datasource.url}") String url,
      @Value("${tributary.datasource.username}") String username,
      @Value("${tributary.datasource.password}") String password) {
    DataSource dataSource = DataSourceFactory.create(url, username, password);
    FlywayMigrator.migrate(dataSource);
    return dataSource;
  }

  @Bean
  public PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new JdbcTransactionManager(dataSource);
  }
}
