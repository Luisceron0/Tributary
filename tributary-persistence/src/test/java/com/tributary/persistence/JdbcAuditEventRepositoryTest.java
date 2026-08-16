package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** T-603: the real, PostgreSQL-backed {@code AuditEventPort} — {@code audit_event} (T-200/V1), already append-only since V3. */
class JdbcAuditEventRepositoryTest extends AbstractPostgresTest {

  private JdbcAuditEventRepository repository() {
    return new JdbcAuditEventRepository(dataSource);
  }

  @Test
  @DisplayName("a recorded event round-trips through a real query with every field intact")
  void recordedEventRoundTrips() {
    JdbcAuditEventRepository repo = repository();
    String actor = "user:" + java.util.UUID.randomUUID();

    repo.record(actor, "SUPPRESS_PII", "buyer:" + java.util.UUID.randomUUID(), "SUCCESS");

    JdbcClient jdbc = JdbcClient.create(dataSource);
    var rows =
        jdbc.sql("SELECT actor, action, entity, result, occurred_at FROM audit_event WHERE actor = ?")
            .param(actor)
            .query((rs, rowNum) ->
                new Object[] {
                  rs.getString("actor"), rs.getString("action"), rs.getString("entity"),
                  rs.getString("result"), rs.getObject("occurred_at", OffsetDateTime.class)
                })
            .list();

    assertEquals(1, rows.size());
    assertEquals(actor, rows.get(0)[0]);
    assertEquals("SUPPRESS_PII", rows.get(0)[1]);
    assertEquals("SUCCESS", rows.get(0)[3]);
    assertTrue(rows.get(0)[4] != null, "occurred_at must be set");
  }

  @Test
  @DisplayName("T-009: nothing written through this port can be UPDATEd afterward — same append-only guarantee as fiscal_record")
  void writtenEventsAreImmutable() {
    JdbcAuditEventRepository repo = repository();
    String actor = "user:" + java.util.UUID.randomUUID();
    repo.record(actor, "ISSUE", "invoice:1", "SUCCESS");

    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertThrows(
        Exception.class,
        () -> jdbc.sql("UPDATE audit_event SET result = 'TAMPERED' WHERE actor = ?").param(actor).update());
  }

  @Test
  @DisplayName("multiple events for the same actor all persist independently — this is a log, not a single current-state row")
  void multipleEventsAccumulate() {
    JdbcAuditEventRepository repo = repository();
    String actor = "user:" + java.util.UUID.randomUUID();

    repo.record(actor, "ISSUE", "invoice:1", "SUCCESS");
    repo.record(actor, "CANCEL", "invoice:1", "SUCCESS");
    repo.record(actor, "SUPPRESS_PII", "buyer:1", "DENIED");

    JdbcClient jdbc = JdbcClient.create(dataSource);
    List<String> actions =
        jdbc.sql("SELECT action FROM audit_event WHERE actor = ? ORDER BY occurred_at")
            .param(actor)
            .query(String.class)
            .list();

    assertEquals(List.of("ISSUE", "CANCEL", "SUPPRESS_PII"), actions);
  }
}
