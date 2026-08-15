package com.tributary.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for every persistence test: a real PostgreSQL 16 container (SRS 8 pins the version; lesson
 * L-004/copilot-instructions.md — "a trigger tested against H2 is untested"), migrated once per
 * test class via a static container + {@code @BeforeAll}.
 */
@Testcontainers
abstract class AbstractPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("tributary")
          .withUsername("tributary_owner")
          .withPassword("test-only-" + System.nanoTime());

  static DataSource dataSource;

  @BeforeAll
  static void migrateSchema() {
    dataSource =
        DataSourceFactory.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    FlywayMigrator.migrate(dataSource);
  }
}
