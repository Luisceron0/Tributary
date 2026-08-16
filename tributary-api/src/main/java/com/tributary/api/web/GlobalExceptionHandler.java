package com.tributary.api.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Structural request validation ({@code @Valid} — missing/malformed fields) is a different,
 * earlier failure than an EN 16931 business rule rejection (T-104's {@code RuleViolation}s, which
 * the SRS's endpoint table reserves {@code 422} for specifically) — this maps to {@code 400}, not
 * {@code 422}, so the two never collapse into the same status code for two genuinely different
 * reasons.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
    List<Map<String, String>> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> Map.of("field", fieldError.getField(), "message", messageOf(fieldError)))
            .toList();
    return ResponseEntity.badRequest().body(Map.of("errors", errors));
  }

  private static String messageOf(FieldError fieldError) {
    String message = fieldError.getDefaultMessage();
    return message == null ? "invalid value" : message;
  }
}
