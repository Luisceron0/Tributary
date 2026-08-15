package com.tributary.persistence;

import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Runs the versioned migrations under {@code db/migration} (T-200). */
public final class FlywayMigrator {

  private FlywayMigrator() {}

  public static void migrate(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Flyway.configure().dataSource(dataSource).load().migrate();
  }
}
