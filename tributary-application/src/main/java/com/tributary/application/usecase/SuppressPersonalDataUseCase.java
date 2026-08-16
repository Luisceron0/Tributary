package com.tributary.application.usecase;

import com.tributary.application.port.AuditEventPort;
import com.tributary.application.port.KeyVaultPort;
import com.tributary.application.port.RetentionCheckPort;
import java.util.Objects;
import java.util.UUID;

/**
 * RF-007: crypto-shredding. Takes a bare {@code buyerId} rather than a {@link
 * com.tributary.domain.Buyer} — there is no PII input to this use case at all, by construction,
 * which is also why the audit trail it writes structurally cannot leak the data being suppressed
 * (T-009's "never from the request body" concern doesn't even apply here: there is no PII in the
 * request to begin with).
 */
public final class SuppressPersonalDataUseCase {

  private final RetentionCheckPort retentionCheck;
  private final KeyVaultPort keyVault;
  private final AuditEventPort auditLog;

  public SuppressPersonalDataUseCase(RetentionCheckPort retentionCheck, KeyVaultPort keyVault, AuditEventPort auditLog) {
    this.retentionCheck = Objects.requireNonNull(retentionCheck, "retentionCheck must not be null");
    this.keyVault = Objects.requireNonNull(keyVault, "keyVault must not be null");
    this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
  }

  /**
   * @param actor the validated actor performing this ADMIN-only operation (T-009: never taken
   *     from a request body — phase 7 wires the real extraction, this use case just takes it as
   *     given, the same split every other T-6xx port in this phase uses)
   * @param justification RF-007's own requirement: the audit trail must record why, without ever
   *     recording the PII itself
   */
  public SuppressPersonalDataResult suppress(UUID buyerId, String actor, String justification) {
    Objects.requireNonNull(buyerId, "buyerId must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(justification, "justification must not be null");

    String entity = "buyer:" + buyerId + " justification:" + justification;

    if (!keyVault.hasKey(buyerId)) {
      auditLog.record(actor, "SUPPRESS_PII", entity, "ALREADY_SUPPRESSED");
      return new SuppressPersonalDataResult.AlreadySuppressed(buyerId);
    }

    if (retentionCheck.hasActiveRetentionObligation(buyerId)) {
      auditLog.record(actor, "SUPPRESS_PII", entity, "DENIED");
      return new SuppressPersonalDataResult.Blocked(buyerId, "an active retention obligation blocks suppression");
    }

    keyVault.destroyKey(buyerId);
    auditLog.record(actor, "SUPPRESS_PII", entity, "SUCCESS");
    return new SuppressPersonalDataResult.Suppressed(buyerId);
  }
}
