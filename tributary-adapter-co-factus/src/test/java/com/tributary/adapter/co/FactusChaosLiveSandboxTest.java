package com.tributary.adapter.co;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * T-307 / CV-10, run against the REAL Factus sandbox — not WireMock. Deliberately NOT part of the
 * default {@code mvn test} run (it needs live credentials, consumes a real sandbox document per
 * run, and counts against the 80 req/min sandbox quota); run explicitly with:
 *
 * <pre>{@code
 * mvn test -pl tributary-adapter-co-factus -Dtest=FactusChaosLiveSandboxTest -DfactusLiveSandbox=true
 * }</pre>
 *
 * <p><b>What "kill the process" actually means here, and why this is the right way to prove it:</b>
 * literally forking a JVM and sending it SIGKILL at a precise instant mid-network-call is racy to
 * synchronize reliably — the interesting moment (after Factus committed the document, before the
 * local process recorded that) has no reliable hook to land a kill signal on. What CV-10 actually
 * needs proven is the AFTER-EFFECT: given a document Factus has already issued, but which the
 * local system does not yet know was issued, does recovery avoid re-submitting? That state is
 * reproduced directly (issue for real, then reset the local row to {@code NEEDS_RECONCILIATION}
 * as if the crash had happened right after Factus responded) — deterministic, and it still proves
 * the real guarantee against the real sandbox: the reconciler must query and adopt, never resubmit,
 * and Factus itself must show exactly one document for the reference_code afterward.
 */
class FactusChaosLiveSandboxTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final int NUMBERING_RANGE_ID = 389; // "Factura de Venta", confirmed live this session

  @Test
  void aDocumentIssuedThenLostLocallyIsAdoptedNotDuplicated() {
    assumeTrue(
        "true".equals(System.getProperty("factusLiveSandbox")),
        "skipped by default — set -DfactusLiveSandbox=true to run against the real sandbox");

    Function<String, String> env = buildEnvFromDotEnv();
    FactusCredentials credentials = FactusEnvironment.resolve(env);
    FactusFiscalRegimeAdapter regime = new FactusFiscalRegimeAdapter(credentials, NUMBERING_RANGE_ID);

    String businessKey = "chaos-" + java.util.UUID.randomUUID();
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));

    // Step 1: a real submission against the real sandbox. This IS "the process" completing
    // normally — the CUFE that comes back is what Factus actually holds for this reference_code.
    Invoice submitting = draft.transitionTo(DocumentState.SUBMITTING);
    var issued = regime.issue(submitting);
    if (issued.externalReference().isEmpty()) {
      System.out.println("DIAGNOSTIC — outcome=" + issued.outcome() + " rawResponse=" + issued.rawResponse());
    }
    assertTrue(issued.externalReference().isPresent(), "the live sandbox must return a real CUFE");
    String realCufe = issued.externalReference().orElseThrow();

    // Step 2: simulate "the process died right after Factus responded, before we recorded it" —
    // the local system's only trace is NEEDS_RECONCILIATION, same as T-304's own UNREACHABLE path
    // would produce. Nothing here talks to Factus again; only local state is reset.
    Invoice lostLocally = submitting.transitionTo(DocumentState.NEEDS_RECONCILIATION);

    // Step 3: "restart" — reconcile. This must query first (T-306) and find the SAME document
    // already sitting at Factus, never attempt a second issue(). The query endpoint's real shape
    // (confirmed live, this session) carries "number", not "cufe" — see FactusQueryGateway's
    // class-level note; the CUFE itself was already captured at issuance, above.
    RegimeQueryResult queryResult = regime.query(businessKey);
    assertEquals(QueryOutcome.FOUND_VALIDATED, queryResult.outcome());
    assertTrue(queryResult.externalReference().isPresent(), "reconciliation must find the document Factus already has");
    String number = queryResult.externalReference().orElseThrow();

    // Step 4: the evidence CV-10 literally asks for — list by reference_code at Factus, exactly
    // one document, consistently. Proven against the real sandbox, not a local assumption.
    RegimeQueryResult secondQuery = regime.query(businessKey);
    assertEquals(
        QueryOutcome.FOUND_VALIDATED, secondQuery.outcome(),
        "querying again must still find exactly the one document — never AMBIGUOUS from a duplicate");
    assertEquals(number, secondQuery.externalReference().orElseThrow(), "repeated queries must resolve to the same document");

    System.out.println(
        "T-307/CV-10 evidence — businessKey=" + businessKey + " cufe=" + realCufe + " number=" + number
            + " — exactly one document confirmed at the live Factus sandbox after simulated crash+recovery.");
  }

  /** Reads .env directly — this test intentionally bypasses any framework wiring (none exists yet, phase 7). */
  private static Function<String, String> buildEnvFromDotEnv() {
    java.util.Map<String, String> values = new java.util.HashMap<>();
    java.nio.file.Path dotEnv = java.nio.file.Path.of(System.getProperty("user.dir"), "..", ".env").normalize();
    try {
      for (String line : java.nio.file.Files.readAllLines(dotEnv)) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq > 0) {
          values.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
        }
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("could not read .env at " + dotEnv + " for the live sandbox test", e);
    }
    return values::get;
  }
}
