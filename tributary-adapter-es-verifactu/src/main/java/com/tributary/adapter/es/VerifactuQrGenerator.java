package com.tributary.adapter.es;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * T-403 (ADR-007, CV-12): the QR keeps the field structure the Verifactu regime expects, but it
 * points to this system's OWN verification endpoint, never the AEAT's — this system does not
 * submit anything to the AEAT (ADR-005), so a QR claiming otherwise would assert something that
 * never happened (lesson L-001). The non-submitted legend rides along with the URL as a
 * non-negotiable part of the content, not an optional caption a caller could drop.
 */
public final class VerifactuQrGenerator {

  public static final String NON_SUBMITTED_LEGEND =
      "Factura no remitida a la Agencia Tributaria. Verificación disponible únicamente en el sistema emisor.";

  /**
   * Known AEAT hosts. Checked against on the way IN (a base URL pointing here is refused outright,
   * not just caught by a downstream test) — CV-12's guarantee belongs to the code, not only to the
   * test suite that watches it.
   */
  private static final List<String> AEAT_HOST_DENYLIST =
      List.of("agenciatributaria.es", "agenciatributaria.gob.es", "aeat.es");

  private VerifactuQrGenerator() {}

  public static VerifactuQrContent contentFor(UUID recordId, String verifierBaseUrl) {
    Objects.requireNonNull(recordId, "recordId must not be null");
    Objects.requireNonNull(verifierBaseUrl, "verifierBaseUrl must not be null");

    String normalizedBaseUrl = verifierBaseUrl.toLowerCase(Locale.ROOT);
    for (String aeatHost : AEAT_HOST_DENYLIST) {
      if (normalizedBaseUrl.contains(aeatHost)) {
        throw new IllegalArgumentException(
            "refusing to build a Verifactu QR pointing at an AEAT host (%s): ADR-007 requires it to point at this system's own verifier"
                .formatted(aeatHost));
      }
    }

    String url = verifierBaseUrl + "/api/v1/records/" + recordId + "/verification";
    return new VerifactuQrContent(url, NON_SUBMITTED_LEGEND);
  }

  public static byte[] generatePng(VerifactuQrContent content, int sizePixels) {
    Objects.requireNonNull(content, "content must not be null");
    String encoded = content.verificationUrl() + "\n" + content.legend();

    try {
      BitMatrix matrix =
          new QRCodeWriter().encode(encoded, BarcodeFormat.QR_CODE, sizePixels, sizePixels);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MatrixToImageWriter.writeToStream(matrix, "PNG", out);
      return out.toByteArray();
    } catch (WriterException | IOException e) {
      // QR encoding failing for well-formed UTF-8 input this class itself constructs is not a
      // recoverable runtime condition — there is no valid caller response other than "this is
      // broken", so it surfaces unchecked rather than forcing every caller to handle it.
      throw new IllegalStateException("failed to encode Verifactu QR", e);
    }
  }
}
