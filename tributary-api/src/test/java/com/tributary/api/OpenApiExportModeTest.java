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

/**
 * T-707: the inverse of {@code RbacAndJwtIntegrationTest#openApiDocsAreNotPubliclyReachableByDefault}
 * — proves the export gate genuinely toggles both ways, not just that it's closed by default. Only
 * {@code scripts/export-openapi.sh} sets this property against a throwaway, non-networked local
 * instance; a real deployment never does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiExportModeTest {

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
    registry.add("tributary.openapi.export-enabled", () -> "true");
  }

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Test
  @DisplayName("with the export flag on, /v3/api-docs serves a real OpenAPI 3.1 document listing the real endpoints")
  void openApiDocsAreReachableWithExportFlagEnabled() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/v3/api-docs", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"openapi\":\"3.1.0\"").contains("/api/v1/invoices");
  }
}
