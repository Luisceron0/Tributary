package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RegisterInvoiceUseCase}: RF-001. The literal verification criterion is "two identical
 * requests produce exactly one document, verifiable by a count in the repository" — this exercises
 * that against {@link InMemoryInvoiceRepository} rather than a real database, since {@code
 * tributary-persistence} doesn't exist yet (phase 2). The repository contract (T-206's real
 * PostgreSQL-backed idempotency) is what phase 2 verifies against a real unique index; this proves
 * the use case's own logic is idempotent independent of what enforces it underneath.
 */
class RegisterInvoiceUseCaseTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  private static RegisterInvoiceRequest requestFor(String saleId) {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    return new RegisterInvoiceRequest(
        saleId, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
  }

  @Test
  @DisplayName("two identical requests produce exactly one document (repository count = 1)")
  void identicalRequestsProduceExactlyOneDocument() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RegisterInvoiceUseCase useCase = new RegisterInvoiceUseCase(repository);
    RegisterInvoiceRequest request = requestFor("sale-001");

    RegisterInvoiceResult first = useCase.execute(request);
    RegisterInvoiceResult second = useCase.execute(request);

    assertInstanceOf(RegisterInvoiceResult.Created.class, first);
    assertInstanceOf(RegisterInvoiceResult.AlreadyDrafted.class, second);

    String businessKey = ((RegisterInvoiceResult.Created) first).invoice().businessKey();
    assertEquals(1, repository.countByBusinessKey(businessKey));
    assertEquals(1, repository.totalSaveCalls(), "the second call must not save again");
    assertSame(
        ((RegisterInvoiceResult.Created) first).invoice(),
        ((RegisterInvoiceResult.AlreadyDrafted) second).existingDraft());
  }

  @Test
  @DisplayName("the same saleId derives the same businessKey across separate calls")
  void sameSaleIdDerivesSameBusinessKey() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RegisterInvoiceUseCase useCase = new RegisterInvoiceUseCase(repository);

    RegisterInvoiceResult first = useCase.execute(requestFor("sale-001"));
    String businessKeyFromFirstCall = ((RegisterInvoiceResult.Created) first).invoice().businessKey();

    // A brand-new use case + repository instance, to prove the key is derived, not cached in memory.
    RegisterInvoiceUseCase anotherUseCase = new RegisterInvoiceUseCase(new InMemoryInvoiceRepository());
    RegisterInvoiceResult independentCall = anotherUseCase.execute(requestFor("sale-001"));
    String businessKeyFromIndependentCall =
        ((RegisterInvoiceResult.Created) independentCall).invoice().businessKey();

    assertEquals(businessKeyFromFirstCall, businessKeyFromIndependentCall);
  }

  @Test
  @DisplayName("different saleIds produce different documents, even with identical content")
  void differentSaleIdsProduceDifferentDocuments() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RegisterInvoiceUseCase useCase = new RegisterInvoiceUseCase(repository);

    RegisterInvoiceResult first = useCase.execute(requestFor("sale-001"));
    RegisterInvoiceResult second = useCase.execute(requestFor("sale-002"));

    assertInstanceOf(RegisterInvoiceResult.Created.class, first);
    assertInstanceOf(RegisterInvoiceResult.Created.class, second);
    assertNotEquals(
        ((RegisterInvoiceResult.Created) first).invoice().businessKey(),
        ((RegisterInvoiceResult.Created) second).invoice().businessKey());
    assertEquals(2, repository.totalSaveCalls());
  }

  @Test
  @DisplayName("invalid business rules are rejected with the complete violation list, nothing persisted")
  void rejectsInvalidRequestsWithoutPersisting() {
    InvoiceLine reverseChargeWithoutBuyerVatId =
        InvoiceLine.reverseCharge(
            "1", "Software licence", Quantity.of("1", "C62"), Money.of("200.00", EUR),
            Money.zero(EUR), "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    RegisterInvoiceRequest request =
        new RegisterInvoiceRequest(
            "sale-001", ISSUER, Buyer.withoutTaxIdentifier("Handel GmbH", "DE"), EUR,
            LocalDate.of(2026, 8, 15), List.of(reverseChargeWithoutBuyerVatId), Money.zero(EUR));

    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    RegisterInvoiceUseCase useCase = new RegisterInvoiceUseCase(repository);

    RegisterInvoiceResult result = useCase.execute(request);

    assertInstanceOf(RegisterInvoiceResult.Invalid.class, result);
    assertTrue(
        ((RegisterInvoiceResult.Invalid) result)
            .violations().stream().anyMatch(v -> v.ruleId().equals("BR-AE-01")));
    assertEquals(0, repository.totalSaveCalls());
  }

  @Test
  @DisplayName("a businessKey that already exists in a non-DRAFT state is a conflict, not a silent overwrite")
  void conflictsWithAnExistingNonDraftDocument() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    RegisterInvoiceRequest request = requestFor("sale-001");
    String businessKey = BusinessKey.derive(ISSUER.taxIdentifier(), "sale-001");

    Invoice alreadyIssued =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR))
            .transitionTo(DocumentState.SUBMITTING)
            .transitionTo(DocumentState.ISSUED);

    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    repository.save(alreadyIssued);

    RegisterInvoiceUseCase useCase = new RegisterInvoiceUseCase(repository);
    RegisterInvoiceResult result = useCase.execute(request);

    assertInstanceOf(RegisterInvoiceResult.Conflict.class, result);
    assertEquals(businessKey, ((RegisterInvoiceResult.Conflict) result).businessKey());
    assertEquals(1, repository.totalSaveCalls(), "the conflicting request must not save a second document");
  }
}
