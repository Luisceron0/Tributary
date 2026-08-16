package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-505 / CV-05: RC-1, RC-2 and RC-3 (see {@code tasks/todo.md}'s reference-case table), mapped
 * to CII by {@link CiiInvoiceMapper} (T-503) and validated for real by {@link KositValidator}
 * (T-504) — the literal criterion RF-005 states: "el XML generado pasa la validación sin
 * advertencias para los tres casos de prueba definidos", checked against the real KoSIT engine,
 * not a self-written approximation of its rules.
 */
class XRechnungReferenceCasesTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final CiiInvoiceMapper MAPPER = new CiiInvoiceMapper();

  private static Path validatorJar;
  private static Path scenariosDirectory;

  @BeforeAll
  static void installValidator() throws Exception {
    Path repoRoot = Path.of(System.getProperty("user.dir"), "..").normalize();
    Process install =
        new ProcessBuilder("bash", repoRoot.resolve("scripts/install-kosit-validator.sh").toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(install.getInputStream().readAllBytes());
    if (install.waitFor() != 0) {
      throw new IllegalStateException("install-kosit-validator.sh failed:\n" + output);
    }
    validatorJar = repoRoot.resolve("validator/validator-1.6.2-standalone.jar");
    scenariosDirectory = repoRoot.resolve("validator/scenarios");
  }

  private void assertAccepted(String caseName, Invoice invoice, Path tempDir) throws Exception {
    String xml = MAPPER.toXml(invoice);
    Path xmlFile = tempDir.resolve(caseName + ".xml");
    Files.writeString(xmlFile, xml, StandardCharsets.UTF_8);

    KositValidator validator = new KositValidator(validatorJar, scenariosDirectory);
    var result = validator.validate(xmlFile, tempDir.resolve("out"));

    System.out.println(
        "T-505/CV-05 evidence — " + caseName + ": accepted=" + result.accepted() + " exitCode=" + result.exitCode()
            + (result.accepted() ? "" : " findings=" + result.findings()));

    assertTrue(
        result.accepted(),
        caseName + " was REJECTED by the real KoSIT validator — findings: " + result.findings() + "\n\nXML:\n" + xml);
  }

  @Test
  @DisplayName("RC-1: standard, one line, 19%, no discounts")
  void rc1IsAccepted(@TempDir Path tempDir) throws Exception {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("rc1-biz-key", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    assertAccepted("rc1", invoice, tempDir);
  }

  @Test
  @DisplayName("RC-2: multi-line, multi-rate, with line and document discounts")
  void rc2IsAccepted(@TempDir Path tempDir) throws Exception {
    InvoiceLine line1 =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("2", "C62"), Money.of("25.00", EUR), Money.of("5.00", EUR),
            TaxRate.ofPercent("19"));
    InvoiceLine line2 =
        InvoiceLine.standardRate(
            "2", "Gadgets", Quantity.of("1", "C62"), Money.of("30.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    InvoiceLine line3 =
        InvoiceLine.standardRate(
            "3", "Books", Quantity.of("1", "C62"), Money.of("50.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("7"));
    Invoice invoice =
        Invoice.draft(
            "rc2-biz-key", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line1, line2, line3),
            Money.of("12.50", EUR));

    assertAccepted("rc2", invoice, tempDir);
  }

  @Test
  @DisplayName("RC-3: intra-community supply, reverse charge, mandatory exemption reason and buyer VAT id")
  void rc3IsAccepted(@TempDir Path tempDir) throws Exception {
    InvoiceLine line1 =
        InvoiceLine.reverseCharge(
            "1", "Consulting", Quantity.of("1", "C62"), Money.of("200.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    InvoiceLine line2 =
        InvoiceLine.reverseCharge(
            "2", "Design services", Quantity.of("1", "C62"), Money.of("150.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    Invoice invoice =
        Invoice.draft(
            "rc3-biz-key", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line1, line2), Money.zero(EUR));

    assertAccepted("rc3", invoice, tempDir);
  }
}
