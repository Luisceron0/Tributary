package com.tributary.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Minimal fixture rows shared by the persistence tests — an issuer, a buyer, a draft invoice. */
final class TestFixtures {

  private TestFixtures() {}

  static UUID insertIssuer(DataSource dataSource) {
    UUID id = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql("INSERT INTO issuer (id, name, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, "Acme Exports SL", "ESB12345678", "ES")
        .update();
    return id;
  }

  /**
   * name_encrypted is a placeholder blob, not a real {@code PiiCipher} ciphertext — none of this
   * fixture's six callers ever decrypt it (they only need a buyer row to satisfy invoice's FK);
   * {@link JdbcInvoiceRepositoryTest} and {@link JdbcKeyVaultRepositoryTest}, which DO exercise
   * real encryption/decryption, build their own buyers through the real code path instead.
   */
  static UUID insertBuyer(DataSource dataSource) {
    UUID id = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql("INSERT INTO buyer (id, name_encrypted, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, new byte[] {0}, "DE123456789", "DE")
        .update();
    return id;
  }

  static UUID insertDraftInvoice(DataSource dataSource, UUID issuerId, UUID buyerId, String businessKey) {
    UUID id = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql(
            """
            INSERT INTO invoice
              (id, business_key, state, issuer_id, buyer_id, currency, issue_date,
               sum_of_line_net_amounts, tax_exclusive_amount, tax_total, tax_inclusive_amount,
               amount_due_for_payment)
            VALUES (?, ?, 'DRAFT', ?, ?, 'EUR', '2026-08-15', 100.00, 100.00, 19.00, 119.00, 119.00)
            """)
        .params(id, businessKey, issuerId, buyerId)
        .update();
    return id;
  }

  static UUID insertAcceptedIssuanceAttempt(DataSource dataSource, UUID invoiceId, String externalReference) {
    UUID id = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql(
            """
            INSERT INTO issuance_attempt (id, invoice_id, regime, outcome, external_reference, raw_response)
            VALUES (?, ?, 'CO', 'ACCEPTED', ?, '{}')
            """)
        .params(id, invoiceId, externalReference)
        .update();
    return id;
  }
}
