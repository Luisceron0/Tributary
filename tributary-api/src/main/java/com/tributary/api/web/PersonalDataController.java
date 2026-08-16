package com.tributary.api.web;

import com.tributary.api.dto.PersonalDataSuppressionRequestDto;
import com.tributary.application.usecase.SuppressPersonalDataResult;
import com.tributary.application.usecase.SuppressPersonalDataUseCase;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RF-007 (ADMIN-only): {@code DELETE /api/v1/subjects/{subjectId}/personal-data}. */
@RestController
@RequestMapping("/api/v1/subjects")
public class PersonalDataController {

  private final SuppressPersonalDataUseCase useCase;

  public PersonalDataController(SuppressPersonalDataUseCase useCase) {
    this.useCase = useCase;
  }

  @DeleteMapping("/{subjectId}/personal-data")
  public ResponseEntity<?> suppress(
      @PathVariable UUID subjectId,
      @Valid @RequestBody PersonalDataSuppressionRequestDto request,
      @AuthenticationPrincipal Jwt jwt) {
    SuppressPersonalDataResult result = useCase.suppress(subjectId, jwt.getSubject(), request.justification());
    return switch (result) {
      // Both a fresh suppression and an already-suppressed subject map to 200 idempotent —
      // RF-007's own alternative flow ("clave ya destruida -> operación idempotente, 200 con
      // estado ya alcanzado").
      case SuppressPersonalDataResult.Suppressed suppressed ->
          ResponseEntity.ok(Map.of("subjectId", suppressed.buyerId(), "status", "SUPPRESSED"));
      case SuppressPersonalDataResult.AlreadySuppressed already ->
          ResponseEntity.ok(Map.of("subjectId", already.buyerId(), "status", "ALREADY_SUPPRESSED"));
      case SuppressPersonalDataResult.Blocked blocked ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("subjectId", blocked.buyerId(), "reason", blocked.reason()));
    };
  }
}
