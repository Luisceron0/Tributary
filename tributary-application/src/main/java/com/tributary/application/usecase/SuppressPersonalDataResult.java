package com.tributary.application.usecase;

import java.util.Objects;
import java.util.UUID;

/** The outcome of {@link SuppressPersonalDataUseCase#suppress} — RF-007. */
public sealed interface SuppressPersonalDataResult {

  record Suppressed(UUID buyerId) implements SuppressPersonalDataResult {
    public Suppressed {
      Objects.requireNonNull(buyerId, "buyerId must not be null");
    }
  }

  /** RF-007's own alternative flow: destroying an already-destroyed key succeeds, not an error. */
  record AlreadySuppressed(UUID buyerId) implements SuppressPersonalDataResult {
    public AlreadySuppressed {
      Objects.requireNonNull(buyerId, "buyerId must not be null");
    }
  }

  record Blocked(UUID buyerId, String reason) implements SuppressPersonalDataResult {
    public Blocked {
      Objects.requireNonNull(buyerId, "buyerId must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
