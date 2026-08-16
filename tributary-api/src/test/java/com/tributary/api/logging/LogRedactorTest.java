package com.tributary.api.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T-701: direct unit tests for the safety-net redactor — no Spring context needed, this is pure
 * string transformation.
 */
class LogRedactorTest {

  @Test
  void redactsBearerToken() {
    String message = "auth failed for header Bearer abc123.def456.ghi789";

    String redacted = LogRedactor.redact(message);

    assertThat(redacted).doesNotContain("abc123.def456.ghi789").contains("Bearer [REDACTED]");
  }

  @Test
  void redactsJwtShapedStringEvenWithoutBearerPrefix() {
    // Synthetic fixture, not a real token: the final segment is a literal marker, not a base64url
    // signature, so this can never be mistaken for (or collide with) an actually-leaked credential.
    String jwt =
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJvcGVyYXRvcjphbGljZSJ9.NOT-A-REAL-SECRET-TEST-FIXTURE-ONLY";
    String message = "token was: " + jwt;

    String redacted = LogRedactor.redact(message);

    assertThat(redacted).doesNotContain(jwt).contains("[REDACTED]");
  }

  @Test
  void leavesOrdinaryMessagesUntouched() {
    String message = "invoice rc1-1234 issued for buyer DE123456789";

    assertThat(LogRedactor.redact(message)).isEqualTo(message);
  }

  @Test
  void nullIsPassedThroughAsNull() {
    assertThat(LogRedactor.redact(null)).isNull();
  }
}
