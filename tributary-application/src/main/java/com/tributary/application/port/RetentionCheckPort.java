package com.tributary.application.port;

import java.util.UUID;

/**
 * RF-007's own first step: "se verifica que no exista una obligación de conservación activa que
 * impida la supresión". A single-method port on purpose (a lambda in tests, no fake class needed)
 * — {@code buyerId} is the same opaque, persistence-owned identifier {@link KeyVaultPort} already
 * operates on, not a domain-level key ({@code Buyer} itself has no identity field, the same way
 * {@code Invoice} carries none of its own — the persistence layer's surrogate UUID never crosses
 * into the domain, T-106's own established pattern applied here).
 */
@FunctionalInterface
public interface RetentionCheckPort {

  /**
   * Whether {@code buyerId} currently has something that must block suppression. The real
   * implementation ({@code JdbcInvoiceRepository}, T-602) scopes this to "any invoice not yet in
   * a terminal {@code DocumentState}" — a document mid-transaction, not a multi-year fiscal
   * retention period, which nothing in the schema models yet. Declared as a scope simplification,
   * not silently: a fuller retention-period model is real future work, not implied to exist here.
   */
  boolean hasActiveRetentionObligation(UUID buyerId);
}
