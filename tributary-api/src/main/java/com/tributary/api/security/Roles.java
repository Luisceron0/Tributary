package com.tributary.api.security;

/**
 * SRS 5.3's three roles, separation of duties: {@code OPERATOR} issues/corrects, {@code AUDITOR}
 * only reads/verifies, {@code ADMIN} manages keys/erasure. No role does both issuance and
 * evidence destruction (T-008's own threat).
 */
public final class Roles {

  public static final String AUTHORITY_PREFIX = "ROLE_";

  public static final String OPERATOR = "OPERATOR";
  public static final String AUDITOR = "AUDITOR";
  public static final String ADMIN = "ADMIN";

  private Roles() {}
}
