package com.tributary.persistence;

import com.tributary.application.port.AuditEventPort;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-603: the real {@link AuditEventPort} — {@code audit_event} (T-200), append-only since V3's
 * trigger and restricted to INSERT/SELECT for {@code tributary_app} since V5's grants. This class
 * adds no immutability logic of its own; the database already guarantees it structurally, the
 * same "PostgreSQL is the authority, not the application" split ADR-002 established for {@code
 * fiscal_record}.
 */
public final class JdbcAuditEventRepository implements AuditEventPort {

  private final JdbcClient jdbc;

  public JdbcAuditEventRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  @Override
  public void record(String actor, String action, String entity, String result) {
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(entity, "entity must not be null");
    Objects.requireNonNull(result, "result must not be null");

    jdbc.sql("INSERT INTO audit_event (actor, action, entity, result) VALUES (?, ?, ?, ?)")
        .params(actor, action, entity, result)
        .update();
  }
}
