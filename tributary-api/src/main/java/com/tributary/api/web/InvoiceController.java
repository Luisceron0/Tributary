package com.tributary.api.web;

import com.tributary.adapter.de.CiiInvoiceMapper;
import com.tributary.api.dto.CorrectionRequestDto;
import com.tributary.api.dto.InvoiceMapper;
import com.tributary.api.dto.InvoiceRequestDto;
import com.tributary.api.dto.InvoiceResponseDto;
import com.tributary.application.usecase.CorrectInvoiceResult;
import com.tributary.application.usecase.CorrectInvoiceUseCase;
import com.tributary.application.usecase.GetInvoiceUseCase;
import com.tributary.application.usecase.IssueInvoiceResult;
import com.tributary.application.usecase.IssueInvoiceUseCase;
import com.tributary.application.usecase.RegisterInvoiceResult;
import com.tributary.application.usecase.RegisterInvoiceUseCase;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RF-001, RF-002, RF-004, RF-005: the invoice lifecycle endpoints (SRS endpoint table). */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

  private final RegisterInvoiceUseCase registerInvoiceUseCase;
  private final IssueInvoiceUseCase issueInvoiceUseCase;
  private final CorrectInvoiceUseCase correctInvoiceUseCase;
  private final GetInvoiceUseCase getInvoiceUseCase;
  private final CiiInvoiceMapper ciiInvoiceMapper = new CiiInvoiceMapper();

  public InvoiceController(
      RegisterInvoiceUseCase registerInvoiceUseCase,
      IssueInvoiceUseCase issueInvoiceUseCase,
      CorrectInvoiceUseCase correctInvoiceUseCase,
      GetInvoiceUseCase getInvoiceUseCase) {
    this.registerInvoiceUseCase = registerInvoiceUseCase;
    this.issueInvoiceUseCase = issueInvoiceUseCase;
    this.correctInvoiceUseCase = correctInvoiceUseCase;
    this.getInvoiceUseCase = getInvoiceUseCase;
  }

  // T-801: the real status codes and success schema, declared explicitly. These endpoints return
  // ResponseEntity<?> on purpose — a single endpoint answers with different bodies per outcome —
  // and springdoc cannot infer anything from a wildcard, so without these annotations the
  // generated contract documented a bare untyped `200 OK` for every route: paths and request
  // bodies correct, responses fiction. Found by generating a typed client from the contract and
  // watching it fail to compile (see lessons.md L-030).
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Registered in DRAFT. Idempotent: re-registering the same businessKey returns the existing draft.",
        content = @Content(schema = @Schema(implementation = InvoiceResponseDto.class))),
    @ApiResponse(responseCode = "409", description = "Already registered in a different state", content = @Content()),
    @ApiResponse(responseCode = "422", description = "EN 16931 validation violations", content = @Content())
  })
  @PostMapping
  public ResponseEntity<?> register(@Valid @RequestBody InvoiceRequestDto request) {
    var useCaseRequest = InvoiceMapper.toRegisterRequest(request);
    RegisterInvoiceResult result = registerInvoiceUseCase.execute(useCaseRequest);
    return switch (result) {
      case RegisterInvoiceResult.Created created ->
          ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponseDto.from(created.invoice()));
      case RegisterInvoiceResult.AlreadyDrafted already ->
          ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponseDto.from(already.existingDraft()));
      case RegisterInvoiceResult.Conflict conflict ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(Map.of("businessKey", conflict.businessKey(), "error", "already registered in a different state"));
      case RegisterInvoiceResult.Invalid invalid ->
          ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
              .body(
                  invalid.violations().stream()
                      .map(v -> Map.of("ruleId", v.ruleId(), "message", v.message()))
                      .toList());
    };
  }

  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = InvoiceResponseDto.class))),
    @ApiResponse(responseCode = "404", description = "No invoice with that businessKey", content = @Content())
  })
  @GetMapping("/{businessKey}")
  public ResponseEntity<?> get(@PathVariable String businessKey) {
    return getInvoiceUseCase
        .execute(businessKey)
        .map(invoice -> ResponseEntity.ok(InvoiceResponseDto.from(invoice)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        description = "Issued; body carries the regime-resolved state",
        content = @Content(schema = @Schema(implementation = InvoiceResponseDto.class))),
    @ApiResponse(
        responseCode = "424",
        description = "Regime unreachable — document left in NEEDS_RECONCILIATION, never retried blindly (ADR-003)",
        content = @Content(schema = @Schema(implementation = InvoiceResponseDto.class))),
    @ApiResponse(responseCode = "404", content = @Content()),
    @ApiResponse(responseCode = "409", description = "Invalid state for issuance", content = @Content())
  })
  @PostMapping("/{businessKey}/issuances")
  public ResponseEntity<?> issue(@PathVariable String businessKey) {
    IssueInvoiceResult result = issueInvoiceUseCase.execute(businessKey);
    return switch (result) {
      case IssueInvoiceResult.Issued issued -> {
        // T-304's use case already resolved the regime's answer into the invoice's own final
        // state; UNREACHABLE (T-301/ADR-003) is the one outcome the SRS's endpoint table calls
        // out separately (424, "régimen no disponible") rather than folding into the normal 202.
        HttpStatus status =
            issued.invoice().state() == DocumentState.NEEDS_RECONCILIATION
                ? HttpStatus.FAILED_DEPENDENCY
                : HttpStatus.ACCEPTED;
        yield ResponseEntity.status(status).body(InvoiceResponseDto.from(issued.invoice()));
      }
      case IssueInvoiceResult.NotFound notFound -> ResponseEntity.notFound().build();
      case IssueInvoiceResult.InvalidState invalidState ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  Map.of(
                      "businessKey", invalidState.businessKey(), "actualState", invalidState.actualState().name()));
    };
  }

  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Correcting document created (RF-004: never an edit)", content = @Content()),
    @ApiResponse(responseCode = "404", content = @Content()),
    @ApiResponse(responseCode = "409", description = "Invalid state, or the regime refused the correction", content = @Content())
  })
  @PostMapping("/{businessKey}/corrections")
  public ResponseEntity<?> correct(
      @PathVariable String businessKey, @Valid @RequestBody CorrectionRequestDto request, @AuthenticationPrincipal Jwt jwt) {
    CorrectInvoiceResult result = correctInvoiceUseCase.correct(businessKey, request.reason(), jwt.getSubject());
    return switch (result) {
      case CorrectInvoiceResult.Corrected corrected ->
          ResponseEntity.status(HttpStatus.CREATED)
              .body(Map.of("businessKey", corrected.businessKey(), "correctionReference", corrected.correctionReference()));
      case CorrectInvoiceResult.NotFound notFound -> ResponseEntity.notFound().build();
      case CorrectInvoiceResult.InvalidState invalidState ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  Map.of(
                      "businessKey", invalidState.businessKey(), "actualState", invalidState.actualState().name()));
      case CorrectInvoiceResult.RegimeRefused refused ->
          ResponseEntity.status(HttpStatus.CONFLICT)
              .body(Map.of("businessKey", refused.businessKey(), "reason", refused.rawResponse()));
    };
  }

  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "EN 16931 / XRechnung CII document",
        content = @Content(mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string"))),
    @ApiResponse(responseCode = "404", content = @Content()),
    @ApiResponse(responseCode = "422", description = "Mapping constraint violated — never a silently invalid document", content = @Content())
  })
  @GetMapping(value = "/{businessKey}/renderings/xrechnung", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<?> renderXRechnung(@PathVariable String businessKey) {
    var found = getInvoiceUseCase.execute(businessKey);
    if (found.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Invoice invoice = found.orElseThrow();
    try {
      String xml = ciiInvoiceMapper.toXml(invoice);
      return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml);
    } catch (IllegalArgumentException e) {
      // RF-005's own alternative flow: "422 con el identificador de la regla BR-xx" — here the
      // rejection is a mapping-level constraint (T-503), not an EN16931 business rule id per se,
      // but the same "never a silently invalid document" principle and status code apply.
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
  }
}
