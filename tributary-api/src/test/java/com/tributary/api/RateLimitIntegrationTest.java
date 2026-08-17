package com.tributary.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tributary.api.security.TestJwtSupport;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-900: SRS §6.5's issuance row has always listed {@code 429 limitador propio} as an alternative
 * flow, and until this task no code path could produce one — T-301's Factus limiter waits
 * internally rather than rejecting, which protects the upstream quota but never answers the
 * caller. This test exists to prove the documented status code is now genuinely reachable, which
 * is the difference between a contract that describes the system and one that matches it.
 *
 * <p>Limits are lowered to a handful of permits here rather than firing 120 real requests: the
 * behaviour under test is "the bucket empties and the answer becomes 429 with Retry-After", not
 * the specific production numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitIntegrationTest {

  private static final int PER_IP_LIMIT = 5;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("tributary")
          .withUsername("tributary_owner")
          .withPassword("test-only-" + System.nanoTime());

  private static KeyPair keyPair;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    keyPair = TestJwtSupport.generateKeyPair();
    registry.add("tributary.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("tributary.datasource.username", POSTGRES::getUsername);
    registry.add("tributary.datasource.password", POSTGRES::getPassword);
    registry.add("tributary.security.allowed-hosts", () -> "localhost");
    registry.add(
        "tributary.security.jwt.public-key",
        () -> TestJwtSupport.publicKeyPem((RSAPublicKey) keyPair.getPublic()));
    registry.add("tributary.regime", () -> "ES");
    registry.add("tributary.security.rate-limit.per-ip-per-minute", () -> String.valueOf(PER_IP_LIMIT));
    registry.add("tributary.security.rate-limit.per-client-per-minute", () -> "1000");
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Test
  @DisplayName("T-900: the per-IP bucket empties and the answer becomes 429 with Retry-After — §6.5's declared status code is reachable at last")
  void exceedingThePerIpLimitYields429() {
    String url = "http://localhost:" + port + "/api/v1/records/" + java.util.UUID.randomUUID() + "/verification";
    HttpEntity<Void> request = new HttpEntity<>(new HttpHeaders());

    HttpStatus lastStatus = null;
    String retryAfter = null;
    // One more than the limit: the bucket starts full, so exactly PER_IP_LIMIT succeed.
    for (int i = 0; i <= PER_IP_LIMIT + 2; i++) {
      ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
      lastStatus = HttpStatus.valueOf(response.getStatusCode().value());
      if (lastStatus == HttpStatus.TOO_MANY_REQUESTS) {
        retryAfter = response.getHeaders().getFirst("Retry-After");
        break;
      }
    }

    assertThat(lastStatus)
        .as("the limiter must eventually reject, otherwise §6.5's 429 row is still fiction")
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(retryAfter).as("a 429 without Retry-After tells the caller nothing actionable").isNotNull();
  }

  @Test
  @DisplayName("the limiter rejects before authentication — an unauthenticated flood costs no signature verification")
  void limitAppliesToUnauthenticatedRequestsToo() {
    // Deliberately a route that would answer 401, not 200: reaching 429 here proves the IP limit
    // sits ahead of authentication rather than behind it.
    String url = "http://localhost:" + port + "/api/v1/invoices";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

    boolean sawTooManyRequests = false;
    for (int i = 0; i <= PER_IP_LIMIT + 5; i++) {
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
      if (response.getStatusCode().value() == 429) {
        sawTooManyRequests = true;
        break;
      }
    }

    assertThat(sawTooManyRequests).isTrue();
  }

  @Test
  @DisplayName("an authenticated caller is still subject to the limit — a valid token is not an exemption")
  void authenticatedCallersAreLimitedToo() {
    String token = TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "operator:alice", "OPERATOR");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    String url = "http://localhost:" + port + "/api/v1/invoices/never-existed";

    boolean sawTooManyRequests = false;
    for (int i = 0; i <= PER_IP_LIMIT + 5; i++) {
      ResponseEntity<String> response =
          restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
      if (response.getStatusCode().value() == 429) {
        sawTooManyRequests = true;
        break;
      }
    }

    assertThat(sawTooManyRequests).isTrue();
  }
}
