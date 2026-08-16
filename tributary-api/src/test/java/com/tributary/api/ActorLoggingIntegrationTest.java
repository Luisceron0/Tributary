package com.tributary.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tributary.api.security.TestJwtSupport;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * T-701 correction (lessons.md): against the real, fully-wired {@code SecurityConfig} filter chain
 * — not a unit test that bypasses it. The original bug ("actor" always logged as "anonymous", even
 * for successful authenticated requests) was invisible to {@code RequestLoggingFilterTest} because
 * that test calls the filter directly and sets {@code SecurityContextHolder} itself, never letting
 * Spring's own {@code SecurityContextHolderFilter} clear it the way it does in the real chain. Only
 * a request through the actual wired chain — this test — can prove {@code ActorCaptureFilter} and
 * {@code RequestLoggingFilter} cooperate correctly end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ActorLoggingIntegrationTest {

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

  private ListAppender<ILoggingEvent> appender;
  private Logger accessLogger;

  @BeforeEach
  void attachAppender() {
    accessLogger = (Logger) LoggerFactory.getLogger("tributary.access");
    appender = new ListAppender<>();
    appender.start();
    accessLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    accessLogger.detachAppender(appender);
  }

  private String baseUrl(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void successfulAuthenticatedRequestIsLoggedWithTheRealActorNotAnonymous() {
    String token = TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "operator:alice", "OPERATOR");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    String body =
        """
        {
          "saleId": "actor-log-1",
          "issuer": {"name": "Acme Exports SL", "taxIdentifier": "ESB12345678", "countryCode": "ES"},
          "buyer": {"name": "Handel GmbH", "taxIdentifier": "DE123456789", "countryCode": "DE"},
          "currency": "EUR",
          "issueDate": "2026-08-15",
          "lines": [
            {"lineIdentifier": "1", "itemName": "Widgets", "quantity": 1, "unitCode": "C62",
             "unitPrice": 100.00, "taxCategory": "STANDARD", "taxRate": 19}
          ]
        }
        """;

    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ILoggingEvent event =
        appender.list.stream()
            .filter(e -> "201".equals(e.getMDCPropertyMap().get("http.status")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no 201 request-completed log line was captured"));
    assertThat(event.getMDCPropertyMap().get("actor"))
        .as("the access log must attribute a successful authenticated write to its real actor")
        .isEqualTo("operator:alice");
  }

  @Test
  void deniedRequestFromAWrongRoleIsStillLoggedWithTheRealActor() {
    String token = TestJwtSupport.validToken((RSAPrivateKey) keyPair.getPrivate(), "auditor:carol", "AUDITOR");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);

    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl("/api/v1/invoices/never-existed/issuances"),
            HttpMethod.POST,
            new HttpEntity<>(null, headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    ILoggingEvent event =
        appender.list.stream()
            .filter(e -> "403".equals(e.getMDCPropertyMap().get("http.status")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no 403 request-completed log line was captured"));
    assertThat(event.getMDCPropertyMap().get("actor"))
        .as("a wrong-role attempt is exactly the kind of thing an audit trail must attribute correctly")
        .isEqualTo("auditor:carol");
  }

  @Test
  void unauthenticatedRequestIsStillLoggedAsAnonymous() {
    ResponseEntity<String> response =
        restTemplate.exchange(baseUrl("/api/v1/invoices"), HttpMethod.POST, new HttpEntity<>(null), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    ILoggingEvent event =
        appender.list.stream()
            .filter(e -> "401".equals(e.getMDCPropertyMap().get("http.status")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no 401 request-completed log line was captured"));
    assertThat(event.getMDCPropertyMap().get("actor")).isEqualTo("anonymous");
  }
}
