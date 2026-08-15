package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * RF-008's {@code GET /v2/bills?filter[reference_code]=...} — shape confirmed live against the
 * real sandbox this session: {@code {status, message, data: {data: [...], pagination: {...}}}}.
 *
 * <p>A server/network failure maps to {@link QueryOutcome#AMBIGUOUS}, never {@code NOT_FOUND} —
 * RF-008's reconciler must never treat "I couldn't ask" the same as "I asked and there's nothing
 * there." Confusing the two would let the reconciler retry issuance after a network blip that
 * never actually reached Factus, exactly the duplicate-fiscal-document risk ADR-003 exists to
 * prevent.
 */
final class FactusQueryGateway {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  FactusQueryGateway() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
  }

  FactusQueryGateway(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  RegimeQueryResult query(FactusCredentials credentials, FactusToken token, String businessKey) {
    Objects.requireNonNull(credentials, "credentials must not be null");
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    String encodedKey = URLEncoder.encode(businessKey, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(credentials.baseUrl() + "/v2/bills?filter%5Breference_code%5D=" + encodedKey))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token.accessToken())
            .header("Accept", "application/json")
            .GET()
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      // Could not even ask — never confused with "asked, and there is nothing there."
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    if (response.statusCode() != 200) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    JsonNode matches;
    try {
      matches = objectMapper.readTree(response.body()).path("data").path("data");
    } catch (IOException e) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    if (!matches.isArray() || matches.isEmpty()) {
      return new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    }
    if (matches.size() > 1) {
      return new RegimeQueryResult(QueryOutcome.AMBIGUOUS, Optional.empty(), List.of());
    }

    JsonNode match = matches.get(0);
    boolean isValidated = match.path("is_validated").asBoolean(false);
    if (!isValidated) {
      return new RegimeQueryResult(QueryOutcome.FOUND_REJECTED, Optional.empty(), List.of());
    }
    Optional<String> cufe = match.hasNonNull("cufe") ? Optional.of(match.get("cufe").asText()) : Optional.empty();
    return new RegimeQueryResult(QueryOutcome.FOUND_VALIDATED, cufe, List.of());
  }
}
