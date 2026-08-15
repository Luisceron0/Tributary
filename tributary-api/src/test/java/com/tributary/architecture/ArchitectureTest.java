package com.tributary.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CV-07: the architecture rules that make ADR-001 checkable instead of merely declared. This
 * lives here, not in {@code tributary-domain}, because verifying "the domain doesn't import
 * adapters" requires seeing the adapters — a module that depends on nothing can't check that
 * nothing depends back on it. {@code tributary-api} is the one module the SRS 6.2 dependency
 * graph has depending on all the others, so it is the only place a system-wide rule can run
 * without inventing an eighth module the SRS doesn't call for.
 *
 * <p>Run directly with {@code mvn test -Dtest=ArchitectureTest} (see {@code
 * .github/copilot-instructions.md}).
 */
class ArchitectureTest {

  private static JavaClasses allClasses;

  /**
   * Regime-specific lexemes that must never appear in a type, field or method name inside {@code
   * tributary-domain} (lesson L-002, T-105 extension agreed 2026-08-15). Import isolation (below)
   * proves the domain doesn't reference an adapter's classes; it says nothing about a field
   * declared as {@code private String cufe} — same primitive type, same package, a regime name
   * leaking in through the back door. This closes that gap with a real, binary check instead of
   * the "manual name review" SRS 9A rejects as unverified.
   */
  private static final List<String> FORBIDDEN_LEXEMES =
      List.of(
          "cufe",
          "referencecode",
          "reference_code",
          "numberingrange",
          "numbering_range",
          "factus",
          "dian",
          "aeat",
          "verifactu",
          "xrechnung",
          "kosit");

  @BeforeAll
  static void importClasses() {
    allClasses = new ClassFileImporter().importPackages("com.tributary");
  }

  @Test
  @DisplayName("CV-07: the domain imports no framework, no JDBC and no adapter")
  void domainDoesNotDependOnFrameworksPersistenceOrAdapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.tributary.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "com.fasterxml.jackson..",
                "java.sql..",
                "javax.sql..",
                "jakarta.persistence..",
                "com.tributary.adapter..",
                "com.tributary.persistence..",
                "com.tributary.application..",
                "com.tributary.api..");

    rule.check(allClasses);
  }

  @Test
  @DisplayName("the domain's type, field and method names carry no regime-specific vocabulary (lesson L-002)")
  void domainNamesCarryNoRegimeSpecificVocabulary() {
    ArchRule rule =
        classes()
            .that()
            .resideInAPackage("com.tributary.domain..")
            .should(new UsesNoForbiddenLexemes());

    rule.check(allClasses);
  }

  @Test
  @DisplayName("no adapter package depends on another adapter package")
  void adaptersDoNotDependOnEachOther() {
    ArchRule rule =
        SlicesRuleDefinition.slices()
            .matching("com.tributary.adapter.(*)..")
            .namingSlices("adapter-$1")
            .should()
            .notDependOnEachOther()
            // The adapter packages are still empty — CO/ES/DE land in phases 3-5. Without this,
            // ArchUnit refuses to run a slice rule that matches zero classes (rightly: a rule
            // that always vacuously passes is exactly the decorative-control failure mode lesson
            // L-004 exists to catch). allowEmptyShould only lifts THAT guard — a real violation,
            // once real adapter classes exist, still fails the rule. The falsifiability probe for
            // this task added throwaway classes to two adapter packages to confirm exactly that.
            .allowEmptyShould(true);

    rule.check(allClasses);
  }

  private static final class UsesNoForbiddenLexemes extends ArchCondition<JavaClass> {

    UsesNoForbiddenLexemes() {
      super("use no regime-specific vocabulary in type, field or method names");
    }

    @Override
    public void check(JavaClass javaClass, ConditionEvents events) {
      checkIdentifier(javaClass, "type " + javaClass.getFullName(), javaClass.getSimpleName(), events);
      for (JavaField field : javaClass.getFields()) {
        checkIdentifier(
            javaClass, "field " + javaClass.getFullName() + "#" + field.getName(), field.getName(), events);
      }
      for (JavaMethod method : javaClass.getMethods()) {
        checkIdentifier(
            javaClass,
            "method " + javaClass.getFullName() + "#" + method.getName(),
            method.getName(),
            events);
      }
    }

    private void checkIdentifier(
        JavaClass javaClass, String description, String identifier, ConditionEvents events) {
      String normalised = identifier.toLowerCase(Locale.ROOT);
      for (String lexeme : FORBIDDEN_LEXEMES) {
        if (normalised.contains(lexeme)) {
          events.add(
              SimpleConditionEvent.violated(
                  javaClass,
                  description + " contains forbidden regime-specific lexeme \"" + lexeme + "\""));
        }
      }
    }
  }
}
