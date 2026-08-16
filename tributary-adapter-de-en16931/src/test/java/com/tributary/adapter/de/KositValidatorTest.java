package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-504: the Java-side integration of the real, pinned-and-checksummed KoSIT validator —
 * {@code scripts/install-kosit-validator.sh}'s fail-closed checksum behaviour itself was verified
 * directly this session (real download, a genuine corrupted-checksum canary confirmed via {@code
 * EXIT CODE: 1} with neither artefact installed, then the real pinned checksum restored and
 * re-verified to succeed — see {@code tasks/todo.md} for the transcript). This test proves the
 * other half: that {@link KositValidator} correctly distinguishes a real accepted document from a
 * real rejected one, using the same two official KoSIT reference instances probed manually before
 * writing any code.
 *
 * <p>{@code @BeforeAll} installs the validator the same way Testcontainers pulls {@code
 * postgres:16} automatically — {@code mvn test} stays self-contained, no separate manual step.
 */
class KositValidatorTest {

  private static Path validatorJar;
  private static Path scenariosDirectory;

  @BeforeAll
  static void installValidator() throws Exception {
    Path repoRoot = Path.of(System.getProperty("user.dir"), "..").normalize();
    Process install =
        new ProcessBuilder("bash", repoRoot.resolve("scripts/install-kosit-validator.sh").toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(install.getInputStream().readAllBytes());
    int exit = install.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("install-kosit-validator.sh failed (exit=" + exit + "):\n" + output);
    }
    validatorJar = repoRoot.resolve("validator/validator-1.6.2-standalone.jar");
    scenariosDirectory = repoRoot.resolve("validator/scenarios");
  }

  private Path copyResourceToTemp(String resourceName, Path dir) throws IOException {
    Path target = dir.resolve(resourceName);
    try (InputStream in = getClass().getResourceAsStream("/kosit-samples/" + resourceName)) {
      Files.copy(in, target);
    }
    return target;
  }

  @Test
  @DisplayName("a real, official, KoSIT-conformant CII instance is ACCEPTED")
  void officialValidSampleIsAccepted(@TempDir Path tempDir) throws Exception {
    Path xmlFile = copyResourceToTemp("official-valid-sample.xml", tempDir);
    KositValidator validator = new KositValidator(validatorJar, scenariosDirectory);

    var result = validator.validate(xmlFile, tempDir.resolve("out"));

    assertTrue(result.accepted(), "expected ACCEPT; findings: " + result.findings());
    assertTrue(result.reportXml().contains("valid=\"true\""));
    System.out.println("T-504 evidence (accept) — exitCode=" + result.exitCode() + " accepted=" + result.accepted());
  }

  @Test
  @DisplayName("the same instance with a mandatory field (BT-44, Buyer name) removed is REJECTED, not silently accepted")
  void officialInvalidSampleIsRejected(@TempDir Path tempDir) throws Exception {
    Path xmlFile = copyResourceToTemp("official-invalid-sample.xml", tempDir);
    KositValidator validator = new KositValidator(validatorJar, scenariosDirectory);

    var result = validator.validate(xmlFile, tempDir.resolve("out"));

    assertFalse(result.accepted());
    assertTrue(result.reportXml().contains("valid=\"false\""));
    assertFalse(result.findings().isEmpty());
    System.out.println(
        "T-504 evidence (reject) — exitCode=" + result.exitCode() + " accepted=" + result.accepted()
            + " findings mention BR-07: " + result.findings().stream().anyMatch(f -> f.contains("BR-07")));
  }
}
