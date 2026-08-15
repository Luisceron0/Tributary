package com.tributary.adapter.es;

import java.util.Objects;

/**
 * What a Verifactu QR encodes (T-403, ADR-007): the URL and the mandatory non-submitted-mode
 * legend, kept apart so a caller can render the URL as a scannable code and print the legend
 * beside it without re-parsing one string.
 */
public record VerifactuQrContent(String verificationUrl, String legend) {

  public VerifactuQrContent {
    Objects.requireNonNull(verificationUrl, "verificationUrl must not be null");
    Objects.requireNonNull(legend, "legend must not be null");
  }
}
