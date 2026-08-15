package com.tributary.adapter.es;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-403 / CV-12 (ADR-007): the QR points to this system's own verification endpoint and declares
 * the non-submitted mode — never an AEAT host. Tests decode the ACTUAL rendered PNG back through
 * ZXing's reader, not just the source string before encoding — the strongest form of "the QR
 * doesn't contain an AEAT host" is proving it about the image an inspector would actually scan.
 */
class VerifactuQrGeneratorTest {

  // A representative sample, not exhaustive — real AEAT hosts, used only to prove none of them
  // ever appear in generated output.
  private static final List<String> AEAT_HOSTS =
      List.of("agenciatributaria.es", "agenciatributaria.gob.es", "aeat.es", "sede.agenciatributaria.gob.es");

  private static String decodeQr(byte[] png) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    BinaryBitmap bitmap =
        new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
    Result result = new MultiFormatReader().decode(bitmap);
    return result.getText();
  }

  @Test
  @DisplayName("the verification URL points to this system's own endpoint")
  void urlPointsToOwnVerificationEndpoint() {
    UUID recordId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    VerifactuQrContent content = VerifactuQrGenerator.contentFor(recordId, "https://tributary.example.com");

    assertEquals(
        "https://tributary.example.com/api/v1/records/11111111-1111-1111-1111-111111111111/verification",
        content.verificationUrl());
  }

  @Test
  @DisplayName("CV-12: no AEAT host appears anywhere in the QR content, source string or decoded image")
  void noAeatHostAnywhereInContent() {
    UUID recordId = UUID.randomUUID();
    VerifactuQrContent content = VerifactuQrGenerator.contentFor(recordId, "https://tributary.example.com");
    String combined = (content.verificationUrl() + " " + content.legend()).toLowerCase(Locale.ROOT);

    for (String aeatHost : AEAT_HOSTS) {
      assertFalse(combined.contains(aeatHost), () -> "found AEAT host \"" + aeatHost + "\" in: " + combined);
    }
  }

  @Test
  @DisplayName("CV-12: the non-submitted-mode legend is present and explicit")
  void nonSubmittedLegendIsPresent() {
    VerifactuQrContent content =
        VerifactuQrGenerator.contentFor(UUID.randomUUID(), "https://tributary.example.com");
    assertEquals(VerifactuQrGenerator.NON_SUBMITTED_LEGEND, content.legend());
    assertTrue(content.legend().toLowerCase(Locale.ROOT).contains("no remitida"));
  }

  @Test
  @DisplayName("the generator refuses an AEAT base URL outright — not just a test-side check")
  void refusesAnAeatBaseUrl() {
    for (String aeatHost : AEAT_HOSTS) {
      assertThrows(
          IllegalArgumentException.class,
          () -> VerifactuQrGenerator.contentFor(UUID.randomUUID(), "https://" + aeatHost),
          () -> "should have refused base URL on " + aeatHost);
    }
  }

  @Test
  @DisplayName("the rendered PNG, decoded back through a real QR reader, contains no AEAT host and matches the intended content")
  void decodedPngMatchesContentAndCarriesNoAeatHost() throws Exception {
    UUID recordId = UUID.randomUUID();
    VerifactuQrContent content = VerifactuQrGenerator.contentFor(recordId, "https://tributary.example.com");
    byte[] png = VerifactuQrGenerator.generatePng(content, 300);

    String decoded = decodeQr(png);
    String decodedLower = decoded.toLowerCase(Locale.ROOT);

    assertAll(
        () -> assertTrue(decoded.contains(content.verificationUrl()), "decoded QR must carry the verification URL"),
        () -> assertTrue(decoded.contains(content.legend()), "decoded QR must carry the legend"));
    for (String aeatHost : AEAT_HOSTS) {
      assertFalse(decodedLower.contains(aeatHost), () -> "decoded QR image contains AEAT host " + aeatHost);
    }
  }

  @Test
  @DisplayName("PNG bytes actually decode as a well-formed PNG image of the requested size")
  void pngIsWellFormedAtRequestedSize() throws Exception {
    VerifactuQrContent content =
        VerifactuQrGenerator.contentFor(UUID.randomUUID(), "https://tributary.example.com");
    byte[] png = VerifactuQrGenerator.generatePng(content, 250);

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    assertAll(
        () -> assertEquals(250, image.getWidth()),
        () -> assertEquals(250, image.getHeight()));
  }
}
