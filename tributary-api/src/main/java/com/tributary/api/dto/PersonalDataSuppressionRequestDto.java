package com.tributary.api.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code DELETE /api/v1/subjects/{subjectId}/personal-data} — RF-007. */
public record PersonalDataSuppressionRequestDto(@NotBlank String justification) {}
