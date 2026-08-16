package com.tributary.api.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/v1/invoices/{id}/corrections} — RF-004. */
public record CorrectionRequestDto(@NotBlank String reason) {}
