package com.tributary.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;

/** Builds a pooled {@link DataSource}. Credentials are parameters, never read from a default. */
public final class DataSourceFactory {

  private DataSourceFactory() {}

  public static DataSource create(String jdbcUrl, String username, String password) {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    Objects.requireNonNull(username, "username must not be null");
    Objects.requireNonNull(password, "password must not be null");

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(10);
    return new HikariDataSource(config);
  }
}
