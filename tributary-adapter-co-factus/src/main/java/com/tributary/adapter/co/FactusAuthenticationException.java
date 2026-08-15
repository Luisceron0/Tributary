package com.tributary.adapter.co;

/** Factus rejected the authentication attempt, or the token endpoint returned an unusable response. */
public final class FactusAuthenticationException extends RuntimeException {

  public FactusAuthenticationException(String message) {
    super(message);
  }

  public FactusAuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
