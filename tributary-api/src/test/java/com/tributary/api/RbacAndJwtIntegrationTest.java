package com.tributary.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tributary.api.security.TestJwtSupport;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.BeforeAll;
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
 * T-604/T-605 (CV-08) and T-606 (CV-09), against the real, fully wired Spring Boot application —
 * not a slice test. RC-1's own reference case flows through the real HTTP layer, real Postgres,
 * real RBAC, real JWT verification, end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RbacAndJwtIntegrationTest {

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
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  private String baseUrl(String path) {
    return "http://localhost:" + port + path;
  }

  private HttpHeaders authHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }

  private String operatorToken() {
    return TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "operator:alice", "OPERATOR");
  }

  private String auditorToken() {
    return TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "auditor:carol", "AUDITOR");
  }

  private String adminToken() {
    return TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "admin:dave", "ADMIN");
  }

  private String sampleInvoiceJson(String saleId) {
    return """
        {
          "saleId": "%s",
          "issuer": {"name": "Acme Exports SL", "taxIdentifier": "ESB12345678", "countryCode": "ES"},
          "buyer": {"name": "Handel GmbH", "taxIdentifier": "DE123456789", "countryCode": "DE"},
          "currency": "EUR",
          "issueDate": "2026-08-15",
          "lines": [
            {"lineIdentifier": "1", "itemName": "Widgets", "quantity": 1, "unitCode": "C62",
             "unitPrice": 100.00, "taxCategory": "STANDARD", "taxRate": 19}
          ]
        }
        """
        .formatted(saleId);
  }

  @Test
  @DisplayName("no token at all -> 401, never a silent pass-through")
  void noTokenIsUnauthorized() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.POST, new HttpEntity<>(sampleInvoiceJson("s1"), authHeaders(null)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("CV-08: OPERATOR gets 403 attempting personal-data suppression — issuance and erasure never share a role")
  void operatorCannotSuppressPersonalData() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/subjects/" + java.util.UUID.randomUUID() + "/personal-data"),
            HttpMethod.DELETE,
            new HttpEntity<>("{\"justification\": \"test\"}", authHeaders(operatorToken())),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("CV-08: AUDITOR gets 403 attempting issuance — a read-only role never triggers an irreversible side effect")
  void auditorCannotIssue() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices/never-existed/issuances"),
            HttpMethod.POST,
            new HttpEntity<>(null, authHeaders(auditorToken())),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("CV-09: a token with alg:none is rejected — never trusted as if it were signed")
  void algNoneTokenIsRejected() {
    String forged = TestJwtSupport.algNoneToken("attacker", "ADMIN");

    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.POST, new HttpEntity<>(sampleInvoiceJson("s2"), authHeaders(forged)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("CV-09: an HS256 token signed with the RSA public key's own bytes (algorithm confusion) is rejected")
  void algorithmConfusionTokenIsRejected() {
    String forged =
        TestJwtSupport.hs256TokenSignedWithPublicKeyBytes((RSAPublicKey) keyPair.getPublic(), "attacker", "ADMIN");

    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.POST, new HttpEntity<>(sampleInvoiceJson("s3"), authHeaders(forged)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("ADR-009: GET /records/{id}/verification needs no token at all — genuinely public, not merely permissive")
  void recordVerificationEndpointIsPublic() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/records/" + java.util.UUID.randomUUID() + "/verification"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(null)),
            String.class);

    // 404 (no such record) is fine; 401 would mean the route is not actually public.
    assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("RC-1 end to end: OPERATOR registers and issues, AUDITOR reads and verifies the chain — the real system, not a slice")
  void rc1EndToEndFlow() {
    String saleId = "rc1-" + java.util.UUID.randomUUID();

    ResponseEntity<String> registerResponse =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.POST,
            new HttpEntity<>(sampleInvoiceJson(saleId), authHeaders(operatorToken())), String.class);
    assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String businessKey = com.jayway.jsonpath.JsonPath.read(registerResponse.getBody(), "$.businessKey");

    ResponseEntity<String> issueResponse =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices/" + businessKey + "/issuances"), HttpMethod.POST,
            new HttpEntity<>(null, authHeaders(operatorToken())), String.class);
    assertThat(issueResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    ResponseEntity<String> getResponse =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices/" + businessKey), HttpMethod.GET,
            new HttpEntity<>(authHeaders(auditorToken())), String.class);
    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getResponse.getBody()).contains("ISSUED");

    // T-405 / RF-004's own acceptance criterion: "no existe ninguna ruta en la API que permita
    // modificar un documento emitido" — checked for real, against this SAME now-ISSUED document,
    // not a hypothetical. Neither method has a registered handler at all; SecurityConfig's
    // anyRequest().denyAll() blocks them at the authorization layer before Spring MVC's
    // dispatcher would even look for one — even for an OPERATOR token that legitimately owns
    // every other write on this exact resource.
    for (HttpMethod unsupportedMethod : java.util.List.of(HttpMethod.PUT, HttpMethod.PATCH)) {
      ResponseEntity<String> attempt =
          restTemplate.exchange(
              baseUrl("/api/v1/invoices/" + businessKey), unsupportedMethod,
              new HttpEntity<>("{\"state\":\"REJECTED\"}", authHeaders(operatorToken())), String.class);
      assertThat(attempt.getStatusCode())
          .as(unsupportedMethod + " must never be allowed to modify an issued document")
          .isIn(HttpStatus.FORBIDDEN, HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND);
    }
  }
}
