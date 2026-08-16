package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RF-007 / T-602: crypto-shredding. Real key destruction is {@link
 * com.tributary.persistence.JdbcKeyVaultRepositoryTest}'s job (T-600, already proven against a
 * real database); this exercises the use case's own decision logic — retention check, idempotent
 * re-suppression, the audit trail's shape — against fakes, the same split every other use case in
 * this module already follows.
 */
class SuppressPersonalDataUseCaseTest {

  private static final class RecordingAuditLog implements com.tributary.application.port.AuditEventPort {
    final List<String[]> events = new ArrayList<>();

    @Override
    public void record(String actor, String action, String entity, String result) {
      events.add(new String[] {actor, action, entity, result});
    }
  }

  private static final class InMemoryKeyVault implements com.tributary.application.port.KeyVaultPort {
    private final Set<UUID> liveKeys = new HashSet<>();

    @Override
    public byte[] getOrCreateKey(UUID subjectId) {
      liveKeys.add(subjectId);
      return new byte[32];
    }

    @Override
    public void destroyKey(UUID subjectId) {
      liveKeys.remove(subjectId);
    }

    @Override
    public boolean hasKey(UUID subjectId) {
      return liveKeys.contains(subjectId);
    }
  }

  @Test
  @DisplayName("a subject with no retention obligation and a live key is suppressed, and the key is really gone")
  void suppressesWhenNothingBlocksIt() {
    UUID buyerId = UUID.randomUUID();
    InMemoryKeyVault keyVault = new InMemoryKeyVault();
    keyVault.getOrCreateKey(buyerId);
    RecordingAuditLog auditLog = new RecordingAuditLog();
    SuppressPersonalDataUseCase useCase =
        new SuppressPersonalDataUseCase(id -> false, keyVault, auditLog);

    SuppressPersonalDataResult result = useCase.suppress(buyerId, "admin:alice", "GDPR art. 17 request #42");

    assertInstanceOf(SuppressPersonalDataResult.Suppressed.class, result);
    assertFalse(keyVault.hasKey(buyerId), "the key must actually be destroyed, not just reported as such");
  }

  @Test
  @DisplayName("RF-007's own alternative flow: suppressing an already-suppressed subject is idempotent, not an error")
  void alreadySuppressedIsIdempotent() {
    UUID buyerId = UUID.randomUUID();
    InMemoryKeyVault keyVault = new InMemoryKeyVault(); // no key ever created for this subject
    RecordingAuditLog auditLog = new RecordingAuditLog();
    SuppressPersonalDataUseCase useCase =
        new SuppressPersonalDataUseCase(id -> false, keyVault, auditLog);

    SuppressPersonalDataResult result = useCase.suppress(buyerId, "admin:alice", "repeat request");

    assertInstanceOf(SuppressPersonalDataResult.AlreadySuppressed.class, result);
  }

  @Test
  @DisplayName("an active retention obligation blocks suppression — the key is never touched")
  void blockedByActiveRetentionObligation() {
    UUID buyerId = UUID.randomUUID();
    InMemoryKeyVault keyVault = new InMemoryKeyVault();
    keyVault.getOrCreateKey(buyerId);
    RecordingAuditLog auditLog = new RecordingAuditLog();
    SuppressPersonalDataUseCase useCase =
        new SuppressPersonalDataUseCase(id -> true, keyVault, auditLog);

    SuppressPersonalDataResult result = useCase.suppress(buyerId, "admin:alice", "premature request");

    assertInstanceOf(SuppressPersonalDataResult.Blocked.class, result);
    assertTrue(keyVault.hasKey(buyerId), "a blocked suppression must never touch the key");
  }

  @Test
  @DisplayName("RF-007: the audit trail records who/when/which subject/why, but never the suppressed PII itself")
  void auditTrailNeverCarriesPii() {
    UUID buyerId = UUID.randomUUID();
    InMemoryKeyVault keyVault = new InMemoryKeyVault();
    keyVault.getOrCreateKey(buyerId);
    RecordingAuditLog auditLog = new RecordingAuditLog();
    SuppressPersonalDataUseCase useCase =
        new SuppressPersonalDataUseCase(id -> false, keyVault, auditLog);

    useCase.suppress(buyerId, "admin:alice", "GDPR art. 17 request #42");

    assertEquals(1, auditLog.events.size());
    String[] event = auditLog.events.get(0);
    assertEquals("admin:alice", event[0]);
    assertTrue(event[2].contains(buyerId.toString()), "the entity must identify which subject");
    assertTrue(event[2].contains("GDPR art. 17 request #42"), "the justification must be captured");
    assertEquals("SUCCESS", event[3]);
    // No field here is a name, an email, an address or a phone number — those never existed as
    // inputs to this use case at all (it takes a buyerId, never a Buyer), so there is nothing
    // for the audit trail to leak even by mistake.
  }

  @Test
  @DisplayName("a blocked attempt is still logged, with a DENIED result, not silently dropped")
  void blockedAttemptIsStillAudited() {
    UUID buyerId = UUID.randomUUID();
    InMemoryKeyVault keyVault = new InMemoryKeyVault();
    keyVault.getOrCreateKey(buyerId);
    RecordingAuditLog auditLog = new RecordingAuditLog();
    SuppressPersonalDataUseCase useCase =
        new SuppressPersonalDataUseCase(id -> true, keyVault, auditLog);

    useCase.suppress(buyerId, "admin:alice", "premature request");

    assertEquals(1, auditLog.events.size());
    assertEquals("DENIED", auditLog.events.get(0)[3]);
  }
}
