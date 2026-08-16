package com.tributary.adapter.de;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * T-504: a thin wrapper around the real, pinned-and-checksummed KoSIT validator ({@code
 * scripts/install-kosit-validator.sh}, CV-11) — shells out to it exactly as its own CLI expects,
 * confirmed empirically against the real tool this session (not from its {@code --help} text
 * alone): {@code java -jar <jar> -r <scenarios-dir> -s <scenarios-dir>/scenarios.xml -o
 * <output-dir> -p <files...>}.
 *
 * <p>The pass/fail signal is the report XML's own verdict, not just the process exit code (kept
 * as a cheap first check, but the report is the actual evidence): the root {@code <rep:report
 * valid="...">} attribute reflects the scenario's accept/reject decision (confirmed empirically —
 * a document with only non-fatal Schematron warnings still reports {@code valid="true"}, matching
 * the SRS's "0 errores fatales" criterion, not "0 findings of any kind"), cross-checked against
 * whether {@code <rep:assessment>} contains {@code <rep:accept>} or {@code <rep:reject>}.
 */
public final class KositValidator {

  private static final String REP_NS = "http://www.xoev.de/de/validator/varl/1";

  private final Path validatorJar;
  private final Path scenariosDirectory;

  public KositValidator(Path validatorJar, Path scenariosDirectory) {
    this.validatorJar = validatorJar;
    this.scenariosDirectory = scenariosDirectory;
  }

  /** Runs the real validator against {@code xmlFile}, writing its report into {@code outputDir}. */
  public KositValidationResult validate(Path xmlFile, Path outputDir) throws IOException, InterruptedException {
    Files.createDirectories(outputDir);
    Path scenariosXml = scenariosDirectory.resolve("scenarios.xml");

    ProcessBuilder processBuilder =
        new ProcessBuilder(
            "java",
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            validatorJar.toAbsolutePath().toString(),
            "-r",
            scenariosDirectory.toAbsolutePath().toString(),
            "-s",
            scenariosXml.toAbsolutePath().toString(),
            "-o",
            outputDir.toAbsolutePath().toString(),
            "-p",
            xmlFile.toAbsolutePath().toString());
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();
    String consoleOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();

    String reportFileName = xmlFile.getFileName().toString().replaceFirst("\\.xml$", "") + "-report.xml";
    Path reportPath = outputDir.resolve(reportFileName);
    if (!Files.exists(reportPath)) {
      throw new IOException(
          "KoSIT validator produced no report at " + reportPath + " (exit=" + exitCode + "); console:\n" + consoleOutput);
    }
    String reportXml = Files.readString(reportPath, StandardCharsets.UTF_8);

    Document report;
    try (var input = Files.newInputStream(reportPath)) {
      report = SecureXmlFactory.parse(input);
    } catch (Exception e) {
      throw new IOException("could not parse the KoSIT report at " + reportPath, e);
    }

    Element root = report.getDocumentElement();
    boolean valid = "true".equals(root.getAttribute("valid"));
    NodeList acceptNodes = root.getElementsByTagNameNS(REP_NS, "accept");
    boolean accepted = valid && acceptNodes.getLength() > 0;

    List<String> findings = new ArrayList<>();
    // Schematron rule ids show up as custom-level "flag" attributes on failed-assert-style rows
    // inside the human-readable explanation table; the console table format we already print
    // stays the most reliable extraction of "which rule fired" without depending on internal
    // report HTML structure, so this only surfaces exit code + console text as findings evidence.
    if (!accepted) {
      findings.add("exit=" + exitCode);
      findings.add(consoleOutput.strip());
    }

    return new KositValidationResult(accepted, exitCode, reportXml, findings);
  }

  public record KositValidationResult(boolean accepted, int exitCode, String reportXml, List<String> findings) {}
}
