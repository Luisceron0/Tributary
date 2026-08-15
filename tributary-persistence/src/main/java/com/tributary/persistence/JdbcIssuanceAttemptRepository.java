package com.tributary.persistence;

import com.tributary.application.port.IssuanceAttemptPort;
import com.tributary.application.port.IssuanceResult;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real, PostgreSQL-backed {@link IssuanceAttemptPort}. Must be called BEFORE an invoice's
 * state transitions to {@code ISSUED}/{@code ISSUED_WITH_WARNINGS} — V4's trigger (T-203) enforces
 * that ordering at the database, not just by convention here.
 */
public final class JdbcIssuanceAttemptRepository implements IssuanceAttemptPort {

  private final JdbcClient jdbc;

  public JdbcIssuanceAttemptRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  @Override
  public void record(String businessKey, String regime, IssuanceResult result) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    Objects.requireNonNull(regime, "regime must not be null");
    Objects.requireNonNull(result, "result must not be null");

    UUID invoiceId =
        jdbc.sql("SELECT id FROM invoice WHERE business_key = ?")
            .param(businessKey)
            .query((rs, rowNum) -> (UUID) rs.getObject("id"))
            .single();

    jdbc.sql(
            """
            INSERT INTO issuance_attempt (invoice_id, regime, outcome, external_reference, warnings, raw_response)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """)
        .params(
            invoiceId,
            regime,
            result.outcome().name(),
            result.externalReference().orElse(null),
            warningsAsJsonArray(result.warnings()),
            result.rawResponse())
        .update();
  }

  private static String warningsAsJsonArray(java.util.List<String> warnings) {
    // Hand-built, not a JSON library: this is a flat array of plain strings, none of which the
    // application controls the content of blindly (they come from Factus's own response text,
    // which could — in principle — contain a double quote). Escape defensively rather than trust
    // upstream text to already be safe for direct concatenation.
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < warnings.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(warnings.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return json.append(']').toString();
  }
}
