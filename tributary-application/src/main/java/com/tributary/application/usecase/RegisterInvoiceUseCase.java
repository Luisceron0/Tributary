package com.tributary.application.usecase;

import com.tributary.application.port.InvoiceRepository;
import com.tributary.domain.DocumentState;
import com.tributary.domain.EN16931BusinessRules;
import com.tributary.domain.Invoice;
import com.tributary.domain.RuleViolation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * RF-001: register a cross-border sale as a draft invoice.
 *
 * <p>Order note: RF-001's prose lists "2. validate business rules, 3. calculate totals" — read as
 * strict sequencing, that's backwards from what happens below, since {@link
 * EN16931BusinessRules#validate} needs the invoice's computed totals to check BR-CO-10 in the
 * first place. {@link Invoice#draft} is the only way to build an {@code Invoice}, and it always
 * computes totals with the same formula the rule checks (see {@code EN16931BusinessRulesTest} and
 * lesson from T-104) — so building-then-validating is observationally identical to
 * validating-then-building for every caller: nothing is ever persisted before validation passes,
 * which is the guarantee RF-001 actually cares about.
 */
public final class RegisterInvoiceUseCase {

  private final InvoiceRepository repository;

  public RegisterInvoiceUseCase(InvoiceRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  public RegisterInvoiceResult execute(RegisterInvoiceRequest request) {
    Objects.requireNonNull(request, "request must not be null");

    String businessKey = BusinessKey.derive(request.issuer().taxIdentifier(), request.saleId());

    Optional<Invoice> existing = repository.findByBusinessKey(businessKey);
    if (existing.isPresent()) {
      Invoice found = existing.get();
      return found.state() == DocumentState.DRAFT
          ? new RegisterInvoiceResult.AlreadyDrafted(found)
          : new RegisterInvoiceResult.Conflict(businessKey);
    }

    Invoice candidate =
        Invoice.draft(
            businessKey, request.issuer(), request.buyer(), request.currency(),
            request.issueDate(), request.lines(), request.documentLevelAllowance());

    List<RuleViolation> violations = EN16931BusinessRules.validate(candidate);
    if (!violations.isEmpty()) {
      return new RegisterInvoiceResult.Invalid(violations);
    }

    repository.save(candidate);
    return new RegisterInvoiceResult.Created(candidate);
  }
}
