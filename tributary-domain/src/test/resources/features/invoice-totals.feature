Feature: Invoice totals (RC-1)
  # Toolchain Q: proves cucumber-jvm is genuinely wired into the reactor, not just
  # declared as a dependency. Same worked reference case as InvoiceTotalsTest#rc1StandardSingleLine
  # (SRS's own RC-1) expressed as an executable acceptance criterion, not a replacement for the
  # existing JUnit test — both exercise the same real domain API.

  Scenario: One line, one rate, no discounts
    Given an invoice line "Widgets" with quantity 1 and unit price 100.00 EUR at 19% tax
    When the invoice totals are computed
    Then the tax exclusive amount is 100.00 EUR
    And the tax total is 19.00 EUR
    And the tax inclusive amount is 119.00 EUR
