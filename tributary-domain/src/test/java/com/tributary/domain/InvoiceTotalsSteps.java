package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Currency;
import java.util.List;

/** Step definitions for {@code invoice-totals.feature} — see that file for what this proves. */
public class InvoiceTotalsSteps {

  private static final Currency EUR = Currency.getInstance("EUR");

  private InvoiceLine line;
  private InvoiceTotals totals;

  @Given("an invoice line {string} with quantity {int} and unit price {bigdecimal} EUR at {bigdecimal}% tax")
  public void anInvoiceLine(String itemName, int quantity, java.math.BigDecimal unitPrice, java.math.BigDecimal taxRate) {
    line =
        InvoiceLine.standardRate(
            "1", itemName, Quantity.of(String.valueOf(quantity), "C62"), Money.of(unitPrice.toPlainString(), EUR),
            Money.zero(EUR), TaxRate.ofPercent(taxRate.toPlainString()));
  }

  @When("the invoice totals are computed")
  public void theInvoiceTotalsAreComputed() {
    totals = InvoiceTotals.compute(EUR, List.of(line), Money.zero(EUR));
  }

  @Then("the tax exclusive amount is {bigdecimal} EUR")
  public void theTaxExclusiveAmountIs(java.math.BigDecimal expected) {
    assertEquals(Money.of(expected.toPlainString(), EUR), totals.taxExclusiveAmount());
  }

  @Then("the tax total is {bigdecimal} EUR")
  public void theTaxTotalIs(java.math.BigDecimal expected) {
    assertEquals(Money.of(expected.toPlainString(), EUR), totals.taxTotal());
  }

  @Then("the tax inclusive amount is {bigdecimal} EUR")
  public void theTaxInclusiveAmountIs(java.math.BigDecimal expected) {
    assertEquals(Money.of(expected.toPlainString(), EUR), totals.taxInclusiveAmount());
  }
}
