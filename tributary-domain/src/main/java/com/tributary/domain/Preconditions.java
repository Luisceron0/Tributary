package com.tributary.domain;

import java.util.Objects;

/** Shared null/blank guards for the value objects in this package. */
final class Preconditions {

  private Preconditions() {}

  static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " must not be null");
    String stripped = value.strip();
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return stripped;
  }
}
