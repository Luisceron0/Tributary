package com.tributary.api.web;

import com.tributary.adapter.es.VerifactuHasher;
import com.tributary.api.dto.ChainVerificationView;
import com.tributary.persistence.ChainVerifier;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-006 (AUDITOR-only): {@code GET /api/v1/chains/{chainId}/verification}. Uses {@link
 * ChainVerifier} (T-206, {@code tributary-persistence}) and {@link VerifactuHasher} (the ES
 * adapter) directly rather than through an application-layer port — this controller lives in
 * {@code tributary-api}, the one module SRS 6.2 already has depending on every adapter AND
 * persistence (agreement A-2), the same reason {@code ArchitectureTest} and {@code
 * VerifactuChainIntegrationTest} live here too. Both classes are already fully built, read-only,
 * single-purpose; wrapping them in a new application-layer use case would add indirection with no
 * use case logic to justify it.
 */
@RestController
@RequestMapping("/api/v1/chains")
public class ChainController {

  private final ChainVerifier chainVerifier;

  public ChainController(DataSource dataSource) {
    this.chainVerifier =
        new ChainVerifier(dataSource, (previousHash, canonicalPayload) -> VerifactuHasher.hash(canonicalPayload, previousHash));
  }

  // T-801: the body is a Map by design (two different shapes for INTACT vs BROKEN), so the schema
  // is declared here rather than inferred. See lessons.md L-030.
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description =
            "INTACT with recordsVerified, or BROKEN with brokenRecordId, storedHash, recomputedHash, totalMismatches and recordsVerified",
        content =
            @io.swagger.v3.oas.annotations.media.Content(
                schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ChainVerificationView.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Unknown chain, or a chain with zero records — indistinguishable, so answered honestly as not found",
        content = @io.swagger.v3.oas.annotations.media.Content())
  })
  @GetMapping("/{chainId}/verification")
  public ResponseEntity<?> verify(@PathVariable UUID chainId) {
    ChainVerifier.VerificationResult result = chainVerifier.verify(chainId);
    return switch (result) {
      // A chain with zero records is indistinguishable from "genuinely nothing has ever been
      // issued here" — ChainVerifier itself can't tell "empty" from "unknown id" apart (there is
      // no separate chain registry), so 404 is the honest response rather than a vacuous INTACT.
      case ChainVerifier.VerificationResult.Intact intact when intact.recordsVerified() == 0 ->
          ResponseEntity.notFound().build();
      case ChainVerifier.VerificationResult.Intact intact ->
          ResponseEntity.ok(ChainVerificationView.intact(intact.recordsVerified()));
      case ChainVerifier.VerificationResult.Broken broken ->
          ResponseEntity.ok(
              ChainVerificationView.broken(
                  broken.brokenRecordId(),
                  broken.storedHash(),
                  broken.recomputedHash(),
                  broken.totalMismatches(),
                  broken.recordsVerified()));
    };
  }
}
