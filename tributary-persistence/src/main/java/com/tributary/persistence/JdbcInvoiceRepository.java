package com.tributary.persistence;

import com.tributary.application.port.InvoiceRepository;
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
public final class JdbcInvoiceRepository implements InvoiceRepository {

  private final JdbcClient jdbc;

  public JdbcInvoiceRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
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

  private UUID insertBuyer(Buyer buyer) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO buyer (id, name, tax_identifier, country_code) VALUES (?, ?, ?, ?)")
        .params(id, buyer.name(), buyer.taxIdentifier().orElse(null), buyer.countryCode())
        .update();
    return id;
  }

  private Issuer findIssuerById(UUID id) {
    return jdbc.sql("SELECT name, tax_identifier, country_code FROM issuer WHERE id = ?")
        .param(id)
        .query((rs, rowNum) -> new Issuer(rs.getString("name"), rs.getString("tax_identifier"), rs.getString("country_code")))
        .single();
  }

  private Buyer findBuyerById(UUID id) {
    return jdbc.sql("SELECT name, tax_identifier, country_code FROM buyer WHERE id = ?")
        .param(id)
        .query(
            (rs, rowNum) ->
                new Buyer(
                    rs.getString("name"), Optional.ofNullable(rs.getString("tax_identifier")),
                    rs.getString("country_code")))
        .single();
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
