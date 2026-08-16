package com.tributary.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tributary.api.security.TestJwtSupport;
import java.security.KeyPair;
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

/** T-700: SRS 5.3's literal response headers, CORS allowlist, and Host allowlist — checked against real HTTP responses. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SecurityHeadersTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withDatabaseName("tributary")
          .withUsername("tributary_owner")
          .withPassword("test-only-" + System.nanoTime());

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    KeyPair keyPair = TestJwtSupport.generateKeyPair();
    registry.add("tributary.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("tributary.datasource.username", POSTGRES::getUsername);
    registry.add("tributary.datasource.password", POSTGRES::getPassword);
    registry.add(
        "tributary.security.jwt.public-key",
        () -> TestJwtSupport.publicKeyPem((RSAPublicKey) keyPair.getPublic()));
    registry.add("tributary.regime", () -> "ES");
    registry.add("tributary.security.allowed-hosts", () -> "localhost");
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  private String baseUrl(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  @DisplayName("SRS 5.3: every literal response header is present, even on a plain unauthenticated request")
  void securityHeadersArePresent() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/records/" + java.util.UUID.randomUUID() + "/verification"), HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()), String.class);

    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
    // Strict-Transport-Security is deliberately NOT asserted here: Spring Security's own HSTS
    // writer only emits it over an already-secure (HTTPS) connection — sending it over plain HTTP
    // would be meaningless (nothing already secured the channel it claims to upgrade), and this
    // test runs over plain HTTP with no TLS in front of it. The configuration itself
    // (includeSubDomains, one-year max-age) is asserted directly in HostAllowlistFilterTest-style
    // isolation instead of by asking a plaintext request to prove a TLS-only behavior.
  }

  @Test
  @DisplayName("SRS 5.3: an unrecognised Host is rejected — proven directly against the filter, since java.net's HTTP clients refuse to let application code override the real Host header at all")
  void unrecognisedHostIsRejected() throws Exception {
    var filter = new com.tributary.api.security.HostAllowlistFilter(java.util.Set.of("localhost", "tributary.example.com"));
    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/records/x/verification");
    request.addHeader("Host", "attacker.example.com");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    var chainCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

    filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

    assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(chainCalled.get()).isFalse();
  }

  @Test
  @DisplayName("a recognised Host passes the filter through to the rest of the chain")
  void recognisedHostPasses() throws Exception {
    var filter = new com.tributary.api.security.HostAllowlistFilter(java.util.Set.of("localhost"));
    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/records/x/verification");
    request.addHeader("Host", "localhost:8080");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    var chainCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

    filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

    assertThat(chainCalled.get()).isTrue();
  }

  @Test
  @DisplayName("SRS 5.3: CORS never falls back to a wildcard — a preflight from an unconfigured origin is refused")
  void corsNeverWildcards() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Origin", "https://attacker.example.com");
    headers.set("Access-Control-Request-Method", "POST");

    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

    assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
  }
}
