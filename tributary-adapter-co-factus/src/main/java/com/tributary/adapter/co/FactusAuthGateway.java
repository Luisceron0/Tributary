package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The real {@code POST /oauth/token} call (password grant) — T-300's HTTP transport. Shape
 * confirmed live against the Factus sandbox: request {@code {grant_type, client_id,
 * client_secret, username, password}}, response {@code {token_type, expires_in, access_token,
 * refresh_token}}.
 *
 * <p>{@code client_secret} and {@code password} never appear in a log line or an exception
 * message here (SRS 5.3) — only the base URL and HTTP status make it into failure text.
 */
final class FactusAuthGateway {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  FactusAuthGateway() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
  }

  FactusAuthGateway(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  FactusToken fetchToken(FactusCredentials credentials) {
    Objects.requireNonNull(credentials, "credentials must not be null");

    Map<String, String> body =
        Map.of(
            "grant_type", "password",
            "client_id", credentials.clientId(),
            "client_secret", credentials.clientSecret(),
            "username", credentials.username(),
            "password", credentials.password());

    HttpRequest request;
    String requestBody;
    try {
      requestBody = objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new FactusAuthenticationException("failed to serialise the token request body", e);
    }

    request =
        HttpRequest.newBuilder()
            .uri(URI.create(credentials.baseUrl() + "/oauth/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new FactusAuthenticationException(
          "failed to reach Factus token endpoint at " + credentials.baseUrl(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FactusAuthenticationException("interrupted while authenticating against Factus", e);
    }

    if (response.statusCode() != 200) {
      throw new FactusAuthenticationException(
          "Factus token endpoint returned HTTP " + response.statusCode() + " for " + credentials.baseUrl());
    }

    Instant fetchedAt = Instant.now();
    JsonNode json;
    try {
      json = objectMapper.readTree(response.body());
    } catch (IOException e) {
      throw new FactusAuthenticationException("Factus token response was not valid JSON", e);
    }

    JsonNode accessToken = json.get("access_token");
    JsonNode refreshToken = json.get("refresh_token");
    JsonNode expiresIn = json.get("expires_in");
    if (accessToken == null || refreshToken == null || expiresIn == null) {
      throw new FactusAuthenticationException(
          "Factus token response is missing access_token, refresh_token or expires_in");
    }

    return new FactusToken(
        accessToken.asText(), refreshToken.asText(), fetchedAt.plusSeconds(expiresIn.asLong()));
  }
}
