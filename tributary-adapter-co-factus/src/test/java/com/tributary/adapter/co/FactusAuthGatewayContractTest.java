package com.tributary.adapter.co;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The real HTTP shape, confirmed live against the Factus sandbox during this session (redacted
 * token values, same field names): {@code POST /oauth/token} with a JSON password-grant body,
 * response {@code {token_type, expires_in, access_token, refresh_token}}. WireMock replays that
 * exact shape so this contract stays enforced without hitting the live sandbox on every build.
 *
 * <p>Uses the extension's own instance methods ({@code wireMock.stubFor}/{@code wireMock.verify}),
 * not the static DSL — the static methods talk to a global default client that assumes port 8080
 * unless {@code WireMock.configureFor(...)} is called, which is not this test's setup.
 */
class FactusAuthGatewayContractTest {

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(WireMockConfiguration.wireMockConfig().dynamicPort()).build();

  private FactusCredentials credentials;
  private FactusAuthGateway gateway;

  @BeforeEach
  void setUp() {
    credentials =
        new FactusCredentials(
            wireMock.baseUrl(), "test-client-id", "test-client-secret", "test-user", "test-pass");
    gateway = new FactusAuthGateway();
  }

  @AfterEach
  void resetStubs() {
    wireMock.resetAll();
  }

  @Test
  @DisplayName("sends the exact password-grant body Factus expects")
  void sendsThePasswordGrantBody() {
    wireMock.stubFor(
        post(urlEqualTo("/oauth/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"token_type":"Bearer","expires_in":3600,"access_token":"access-abc","refresh_token":"refresh-xyz"}
                        """)));

    gateway.fetchToken(credentials);

    wireMock.verify(
        postRequestedFor(urlEqualTo("/oauth/token"))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(
                equalToJson(
                    """
                    {"grant_type":"password","client_id":"test-client-id","client_secret":"test-client-secret","username":"test-user","password":"test-pass"}
                    """)));
  }

  @Test
  @DisplayName("parses access_token, refresh_token and computes expiresAt from expires_in")
  void parsesTheTokenResponse() {
    Instant before = Instant.now();
    wireMock.stubFor(
        post(urlEqualTo("/oauth/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"token_type":"Bearer","expires_in":3600,"access_token":"access-abc","refresh_token":"refresh-xyz"}
                        """)));

    FactusToken token = gateway.fetchToken(credentials);
    Instant after = Instant.now();

    assertEquals("access-abc", token.accessToken());
    assertEquals("refresh-xyz", token.refreshToken());
    // expiresAt = fetch time + 3600s, within the window this test executed in.
    assertTrue(!token.expiresAt().isBefore(before.plusSeconds(3600)));
    assertTrue(!token.expiresAt().isAfter(after.plusSeconds(3600)));
  }

  @Test
  @DisplayName("invalid credentials (401) surface as a distinct exception, not a null token")
  void invalidCredentialsSurfaceAsAnException() {
    wireMock.stubFor(
        post(urlEqualTo("/oauth/token"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {"message":"Unauthenticated."}
                        """)));

    assertThrows(FactusAuthenticationException.class, () -> gateway.fetchToken(credentials));
  }

  @Test
  @DisplayName("the client_secret never appears verbatim in a thrown exception's message")
  void clientSecretNeverAppearsInExceptionMessages() {
    wireMock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(500)));

    FactusAuthenticationException exception =
        assertThrows(FactusAuthenticationException.class, () -> gateway.fetchToken(credentials));
    assertTrue(!exception.getMessage().contains("test-client-secret"));
    assertTrue(!exception.getMessage().contains("test-pass"));
  }
}
