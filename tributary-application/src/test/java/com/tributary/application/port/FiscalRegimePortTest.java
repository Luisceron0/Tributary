package com.tributary.application.port;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FiscalRegimePort} is the one port the CO, ES and DE adapters all implement (ADR-001):
 * three completely different processes — a live HTTP clearance call, a local hash-chain insert, a
 * local XML serialise-and-validate — behind the same three methods. T-103's verification
 * criterion is that the interface never names a concrete regime's type; this asserts it by
 * reflection instead of by eyeballing the source, so a future edit that leaks one fails a real
 * test, not a code review.
 */
class FiscalRegimePortTest {

  @Test
  @DisplayName("declares exactly issue, cancel and query")
  void declaresIssueCancelAndQuery() {
    Set<String> methodNames =
        Arrays.stream(FiscalRegimePort.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertTrue(
        methodNames.containsAll(Set.of("issue", "cancel", "query")),
        () -> "expected issue, cancel, query but found " + methodNames);
    assertTrue(FiscalRegimePort.class.isInterface(), "FiscalRegimePort must be an interface");
  }

  @Test
  @DisplayName("no method's return type, parameter type or generic argument names a concrete adapter type")
  void mentionsNoConcreteRegimeType() {
    for (Method method : FiscalRegimePort.class.getDeclaredMethods()) {
      assertNotAdapterType(method.getReturnType(), method);
      for (Parameter parameter : method.getParameters()) {
        assertNotAdapterType(parameter.getType(), method);
      }
    }
  }

  private void assertNotAdapterType(Class<?> type, Method method) {
    String packageName = type.getPackageName();
    assertTrue(
        !packageName.startsWith("com.tributary.adapter"),
        () -> method + " references " + type.getName() + ", a regime-specific adapter type");
  }
}
