package com.tributary.api.web;

import com.tributary.application.usecase.GetRecordVerificationUseCase;
import com.tributary.application.usecase.RecordVerificationView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADR-009: the ONE unauthenticated route in the whole system (see {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/v1/records")
public class RecordController {

  private final GetRecordVerificationUseCase useCase;

  public RecordController(GetRecordVerificationUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping("/{recordId}/verification")
  public ResponseEntity<RecordVerificationView> verify(@PathVariable UUID recordId) {
    return useCase.execute(recordId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }
}
