package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Factus's {@code errors} field, in the one shape the real sandbox actually returns it: an OBJECT
 * keyed by DIAN rule id ({@code {"RUT01": "...", "FAJ44b": "..."}}), never an array.
 *
 * <p>Shared by both gateways on purpose. The issuance path and the reconciliation query path have
 * to read the same field the same way — an audit found them disagreeing (the query path discarded
 * it entirely), which made the same fiscal document land in a different state depending only on
 * whether its original HTTP response happened to arrive. One reader, one interpretation.
 */
final class FactusErrorMessages {

  private FactusErrorMessages() {}

  /** Flattens {@code errors} into {@code "<rule id>: <message>"} lines, empty when there are none. */
  static List<String> from(JsonNode errorsNode) {
    if (errorsNode == null || !errorsNode.isObject() || errorsNode.isEmpty()) {
      return List.of();
    }
    List<String> messages = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> fields = errorsNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      messages.add(entry.getKey() + ": " + entry.getValue().asText());
    }
    return List.copyOf(messages);
  }
}
