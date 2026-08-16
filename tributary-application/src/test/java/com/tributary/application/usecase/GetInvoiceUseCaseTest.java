package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.domain.Buyer;
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

class GetInvoiceUseCaseTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

  @Test
  @DisplayName("returns the saved invoice by its businessKey")
  void returnsTheSavedInvoice() {
    InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice invoice =
        Invoice.draft("biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    repository.save(invoice);
    GetInvoiceUseCase useCase = new GetInvoiceUseCase(repository);

    var result = useCase.execute("biz-key-1");

    assertTrue(result.isPresent());
    assertEquals("biz-key-1", result.orElseThrow().businessKey());
  }

  @Test
  @DisplayName("an unknown businessKey returns empty")
  void unknownBusinessKeyReturnsEmpty() {
    GetInvoiceUseCase useCase = new GetInvoiceUseCase(new InMemoryInvoiceRepository());

    assertTrue(useCase.execute("never-existed").isEmpty());
  }
}
