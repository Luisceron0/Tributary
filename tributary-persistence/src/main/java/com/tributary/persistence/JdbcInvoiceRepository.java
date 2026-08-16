package com.tributary.persistence;

import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.KeyVaultPort;
import com.tributary.application.port.RetentionCheckPort;
import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.InvoiceTotals;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxCategory;
import com.tributary.domain.TaxRate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real, PostgreSQL-backed {@link InvoiceRepository} (T-106's port). Invoice lines are written
 * once, at first save, and never touched again — the domain's own immutability (only {@code
 * state} ever changes across an {@code Invoice}'s life) is mirrored here: a second {@link #save}
 * for an already-known {@code business_key} updates {@code invoice.state} only.
 */
public final class JdbcInvoiceRepository implements InvoiceRepository, RetentionCheckPort {

  private final JdbcClient jdbc;
  private final KeyVaultPort keyVault;

  public JdbcInvoiceRepository(DataSource dataSource, KeyVaultPort keyVault) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.keyVault = Objects.requireNonNull(keyVault, "keyVault must not be null");
  }

  @Override
  public Optional<UUID> findIdByBusinessKey(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    return jdbc.sql("SELECT id FROM invoice WHERE business_key = ?")
        .param(businessKey)
        .query((rs, rowNum) -> (UUID) rs.getObject("id"))
        .optional();
  }

  @Override
  public Optional<Invoice> findByBusinessKey(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    Optional<InvoiceRow> row =
        jdbc.sql(
                """
                SELECT id, business_key, state, issuer_id, buyer_id, currency, issue_date, document_level_allowance
                FROM invoice WHERE business_key = ?
                """)
            .param(businessKey)
            .query(JdbcInvoiceRepository::mapInvoiceRow)
            .optional();

    if (row.isEmpty()) {
      return Optional.empty();
    }
    InvoiceRow r = row.orElseThrow();
    Issuer issuer = findIssuerById(r.issuerId());
    Buyer buyer = findBuyerById(r.buyerId());
    List<InvoiceLine> lines = findLines(r.id(), r.currency());
    InvoiceTotals totals = InvoiceTotals.compute(r.currency(), lines, r.documentLevelAllowance());

    return Optional.of(
        new Invoice(
            r.businessKey(), r.state(), issuer, buyer, r.currency(), r.issueDate(), lines,
            r.documentLevelAllowance(), totals));
  }

  @Override
  public void save(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");

    Optional<UUID> existingId =
        jdbc.sql("SELECT id FROM invoice WHERE business_key = ?")
            .param(invoice.businessKey())
            .query((rs, rowNum) -> (UUID) rs.getObject("id"))
            .optional();

    if (existingId.isPresent()) {
      jdbc.sql("UPDATE invoice SET state = ?, updated_at = now() WHERE id = ?")
          .params(invoice.state().name(), existingId.orElseThrow())
          .update();
      return;
    }

    UUID invoiceId = UUID.randomUUID();
    insertNewInvoice(invoice, invoiceId, upsertIssuer(invoice.issuer()), insertBuyer(invoice.buyer()));
    insertLines(invoiceId, invoice.lines());
  }

  private void insertNewInvoice(Invoice invoice, UUID invoiceId, UUID issuerId, UUID buyerId) {
    jdbc.sql(
            """
            INSERT INTO invoice
              (id, business_key, state, issuer_id, buyer_id, currency, issue_date,
               document_level_allowance, sum_of_line_net_amounts, tax_exclusive_amount, tax_total,
               tax_inclusive_amount, amount_due_for_payment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            invoiceId,
            invoice.businessKey(),
            invoice.state().name(),
            issuerId,
            buyerId,
            invoice.currency().getCurrencyCode(),
            invoice.issueDate(),
            invoice.documentLevelAllowance().amount(),
            invoice.totals().sumOfLineNetAmounts().amount(),
            invoice.totals().taxExclusiveAmount().amount(),
            invoice.totals().taxTotal().amount(),
            invoice.totals().taxInclusiveAmount().amount(),
            invoice.totals().amountDueForPayment().amount())
        .update();
  }

  private void insertLines(UUID invoiceId, List<InvoiceLine> lines) {
    int lineOrder = 0;
    for (InvoiceLine line : lines) {
      jdbc.sql(
              """
              INSERT INTO invoice_line
                (invoice_id, line_order, line_identifier, item_name, quantity, unit_code,
                 unit_price, line_discount, tax_category, tax_rate, vat_exemption_reason)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .params(
              invoiceId,
              lineOrder++,
              line.lineIdentifier(),
              line.itemName(),
              line.quantity().value(),
              line.quantity().unitCode(),
              line.unitPrice().amount(),
              line.lineDiscount().amount(),
              line.taxCategory().name(),
              line.taxRate().percentage(),
              line.vatExemptionReason().orElse(null))
          .update();
    }
  }

  @Override
  public long countByBusinessKey(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    return jdbc.sql("SELECT count(*) FROM invoice WHERE business_key = ?")
        .param(businessKey)
        .query(Long.class)
        .single();
  }

  @Override
  public boolean tryTransition(String businessKey, DocumentState from, DocumentState to) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");

    // The WHERE clause is the compare in compare-and-swap: this UPDATE affects a row only if the
    // persisted state is still exactly `from` at the instant Postgres evaluates it, and Postgres
    // serializes concurrent UPDATEs to the same row — so of N concurrent callers racing this same
    // statement for the same businessKey, exactly one gets rows-affected = 1 and the rest get 0.
    int rowsAffected =
        jdbc.sql("UPDATE invoice SET state = ?, updated_at = now() WHERE business_key = ? AND state = ?")
            .params(to.name(), businessKey, from.name())
            .update();
    return rowsAffected == 1;
  }

  /**
   * T-602 / RF-007: {@code buyerId}'s scope simplification of "an active retention obligation" —
   * see {@link RetentionCheckPort}'s own class note for why. {@code ISSUED},
   * {@code ISSUED_WITH_WARNINGS}, {@code REJECTED} and {@code MANUAL_REVIEW} are {@code
   * DocumentState}'s own terminal states (T-102) — anything else (DRAFT, SUBMITTING,
   * NEEDS_RECONCILIATION) means a document involving this buyer is still mid-transaction.
   */
  @Override
  public boolean hasActiveRetentionObligation(UUID buyerId) {
    Objects.requireNonNull(buyerId, "buyerId must not be null");
    long nonTerminalCount =
        jdbc.sql(
                "SELECT count(*) FROM invoice WHERE buyer_id = ? "
                    + "AND state NOT IN ('ISSUED', 'ISSUED_WITH_WARNINGS', 'REJECTED', 'MANUAL_REVIEW')")
            .param(buyerId)
            .query(Long.class)
            .single();
    return nonTerminalCount > 0;
  }

  private UUID upsertIssuer(Issuer issuer) {
    Optional<UUID> existing =
        jdbc.sql("SELECT id FROM issuer WHERE tax_identifier = ?")
            .param(issuer.taxIdentifier())
            .query((rs, rowNum) -> (UUID) rs.getObject("id"))
            .optional();
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO issuer (id, name, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, issuer.name(), issuer.taxIdentifier(), issuer.countryCode())
        .update();
    return id;
  }

  /**
   * Two statements, not one: {@code subject_key.subject_id} REFERENCES {@code buyer.id} (V6), so
   * the buyer row must exist before {@link KeyVaultPort#getOrCreateKey} can persist a key against
   * it — but the key is needed to encrypt the very fields the INSERT would carry. Nothing else can
   * observe this row between the two statements (its id isn't returned to any other caller until
   * this method itself returns), so the brief placeholder state is not a real inconsistency window.
   */
  private UUID insertBuyer(Buyer buyer) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO buyer (id, name_encrypted, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, new byte[0], buyer.taxIdentifier().orElse(null), buyer.countryCode())
        .update();

    byte[] key = keyVault.getOrCreateKey(id);
    jdbc.sql(
            "UPDATE buyer SET name_encrypted = ?, address_encrypted = ?, email_encrypted = ?, phone_encrypted = ? WHERE id = ?")
        .params(
            PiiCipher.encrypt(key, buyer.name()),
            buyer.address().map(a -> PiiCipher.encrypt(key, a)).orElse(null),
            buyer.email().map(e -> PiiCipher.encrypt(key, e)).orElse(null),
            buyer.phone().map(p -> PiiCipher.encrypt(key, p)).orElse(null),
            id)
        .update();
    return id;
  }

  private Issuer findIssuerById(UUID id) {
    return jdbc.sql("SELECT name, tax_identifier, country_code FROM issuer WHERE id = ?")
        .param(id)
        .query((rs, rowNum) -> new Issuer(rs.getString("name"), rs.getString("tax_identifier"), rs.getString("country_code")))
        .single();
  }

  /**
   * RF-007: once {@code subjectId}'s key has been destroyed (crypto-shredded), the encrypted
   * columns are permanently unreadable — this is what "suppression" means, not an error state to
   * work around. {@code keyVault.hasKey} is checked BEFORE ever calling {@link
   * KeyVaultPort#getOrCreateKey}: that method's own contract is create-on-first-use, so calling it
   * here on a suppressed subject would silently mint a brand new key that could never have
   * decrypted the old ciphertext anyway — the wrong tool for a read path.
   */
  private Buyer findBuyerById(UUID id) {
    BuyerRow row =
        jdbc.sql(
                "SELECT name_encrypted, tax_identifier, country_code, address_encrypted, email_encrypted, phone_encrypted FROM buyer WHERE id = ?")
            .param(id)
            .query(JdbcInvoiceRepository::mapBuyerRow)
            .single();

    if (!keyVault.hasKey(id)) {
      return new Buyer(
          "[SUPPRESSED]", Optional.ofNullable(row.taxIdentifier()), row.countryCode(),
          Optional.empty(), Optional.empty(), Optional.empty());
    }

    byte[] key = keyVault.getOrCreateKey(id);
    return new Buyer(
        decrypt(key, row.nameEncrypted()),
        Optional.ofNullable(row.taxIdentifier()),
        row.countryCode(),
        Optional.ofNullable(row.addressEncrypted()).map(blob -> decrypt(key, blob)),
        Optional.ofNullable(row.emailEncrypted()).map(blob -> decrypt(key, blob)),
        Optional.ofNullable(row.phoneEncrypted()).map(blob -> decrypt(key, blob)));
  }

  private static String decrypt(byte[] key, byte[] blob) {
    try {
      return PiiCipher.decrypt(key, blob);
    } catch (javax.crypto.AEADBadTagException e) {
      // hasKey() was true a moment ago, so this key was never destroyed mid-read — an
      // AEADBadTagException here means the stored ciphertext itself does not match this key,
      // which is a data-integrity problem, not the ordinary "subject was suppressed" path above.
      throw new IllegalStateException("could not decrypt buyer PII with its own subject key", e);
    }
  }

  private record BuyerRow(
      byte[] nameEncrypted, String taxIdentifier, String countryCode, byte[] addressEncrypted,
      byte[] emailEncrypted, byte[] phoneEncrypted) {}

  private static BuyerRow mapBuyerRow(ResultSet rs, int rowNum) throws SQLException {
    return new BuyerRow(
        rs.getBytes("name_encrypted"),
        rs.getString("tax_identifier"),
        rs.getString("country_code"),
        rs.getBytes("address_encrypted"),
        rs.getBytes("email_encrypted"),
        rs.getBytes("phone_encrypted"));
  }

  private List<InvoiceLine> findLines(UUID invoiceId, Currency currency) {
    return jdbc.sql(
            """
            SELECT line_identifier, item_name, quantity, unit_code, unit_price, line_discount,
                   tax_category, tax_rate, vat_exemption_reason
            FROM invoice_line WHERE invoice_id = ? ORDER BY line_order
            """)
        .param(invoiceId)
        .query((rs, rowNum) -> mapInvoiceLine(rs, currency))
        .list();
  }

  private static InvoiceLine mapInvoiceLine(ResultSet rs, Currency currency) throws SQLException {
    return new InvoiceLine(
        rs.getString("line_identifier"),
        rs.getString("item_name"),
        Quantity.of(rs.getBigDecimal("quantity"), rs.getString("unit_code")),
        Money.of(rs.getBigDecimal("unit_price"), currency),
        Money.of(rs.getBigDecimal("line_discount"), currency),
        TaxCategory.valueOf(rs.getString("tax_category")),
        TaxRate.of(rs.getBigDecimal("tax_rate")),
        Optional.ofNullable(rs.getString("vat_exemption_reason")));
  }

  private record InvoiceRow(
      UUID id, String businessKey, DocumentState state, UUID issuerId, UUID buyerId,
      Currency currency, LocalDate issueDate, Money documentLevelAllowance) {}

  private static InvoiceRow mapInvoiceRow(ResultSet rs, int rowNum) throws SQLException {
    Currency currency = Currency.getInstance(rs.getString("currency"));
    return new InvoiceRow(
        (UUID) rs.getObject("id"),
        rs.getString("business_key"),
        DocumentState.valueOf(rs.getString("state")),
        (UUID) rs.getObject("issuer_id"),
        (UUID) rs.getObject("buyer_id"),
        currency,
        rs.getDate("issue_date").toLocalDate(),
        Money.of(rs.getBigDecimal("document_level_allowance"), currency));
  }
}
