package com.tributary.adapter.es;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit of the canonical form's delimiter handling — and an honest record of what the audit
 * actually found, which is less than it first looked like.
 *
 * <p><b>The concern.</b> The canonical form is a delimited string ({@code FIELD=value|FIELD=value})
 * whose values are interpolated without escaping the delimiters that separate them. Two of those
 * values are free text arriving over HTTP: the correction {@code reason} (straight from the request
 * body via {@code InvoiceController}) and the buyer's tax identifier. Unescaped delimiters in a
 * signed, delimited representation are a well-known source of collisions, and the canonical string
 * is the authoritative statement of what was recorded (ADR-002, RF-006) — if two different records
 * could canonicalize identically, a hash would no longer identify which invoice it attests to, and
 * the chain verifier could not detect it: it would faithfully recompute the hash of an ambiguous
 * string and correctly report INTACT.
 *
 * <p><b>What was actually found: no reachable collision.</b> Attempts to construct one failed, and
 * the reason is structural rather than lucky. The field count and field order are fixed, so
 * injecting a delimiter adds separators rather than substituting them — the two strings end up
 * different lengths with different separator counts. The trailing field is an {@link Instant}
 * rendered by {@code toString()}, which cannot contain a delimiter, so the tail of every canonical
 * string is format-constrained and cannot be forged from a free-text field. Nothing parses the
 * canonical form back into fields either; it is only ever hashed and compared, so parsing ambiguity
 * has no path to becoming a wrong answer.
 *
 * <p><b>Why these tests exist anyway.</b> That safety is a consequence of the current field layout,
 * not of anything the code states or enforces. Adding a free-text field at the end, making the
 * field set variable, or reordering so two free-text fields become adjacent would each remove it
 * silently. These cases pin the property — different data yields a different canonical form — so
 * such a change fails here instead of in a fiscal record years later.
 */
class VerifactuCanonicalInjectionTest {

  private static final Instant GENERATED_AT = Instant.parse("2026-08-15T10:00:00Z");

  @Test
  @DisplayName("a correction reason carrying the delimiters cannot impersonate a different record")
  void reasonCannotForgeAdjacentFields() {
    String canonicalA =
        VerifactuHasher.canonicalizeAnulacion(
            "abc123", "duplicado|GENERATED_AT=1999-01-01T00:00:00Z", GENERATED_AT);
    String canonicalB =
        VerifactuHasher.canonicalizeAnulacion("abc123", "duplicado", Instant.parse("1999-01-01T00:00:00Z"));

    assertNotEquals(
        canonicalA,
        canonicalB,
        "two cancellations with different data canonicalized identically — the hash would no "
            + "longer identify which record it attests to");
  }

  @Test
  @DisplayName("the referenced alta hash and the reason cannot be made to blur into each other")
  void reasonCannotAbsorbTheReferencedHash() {
    String canonicalA = VerifactuHasher.canonicalizeAnulacion("abc", "123|REASON=duplicado", GENERATED_AT);
    String canonicalB = VerifactuHasher.canonicalizeAnulacion("abc123", "duplicado", GENERATED_AT);

    assertNotEquals(canonicalA, canonicalB, "a forged reason reproduced a different record's canonical form");
  }

  @Test
  @DisplayName("distinct reasons still hash distinctly — the separation is not lossy")
  void distinctValuesHashDistinctly() {
    String hashA = VerifactuHasher.hash(VerifactuHasher.canonicalizeAnulacion("abc", "a|b", GENERATED_AT), Optional.empty());
    String hashB = VerifactuHasher.hash(VerifactuHasher.canonicalizeAnulacion("abc", "a\\|b", GENERATED_AT), Optional.empty());

    assertNotEquals(hashA, hashB, "these are different reasons and must not share a hash");
  }
}
