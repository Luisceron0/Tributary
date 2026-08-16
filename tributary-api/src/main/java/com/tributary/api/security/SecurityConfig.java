package com.tributary.api.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * T-604/T-605/T-606 (CV-08/CV-09): RBAC by route, and JWT verification restricted to exactly one
 * algorithm.
 *
 * <p>{@link #jwtDecoder} is built with {@code .signatureAlgorithm(RS256)} explicitly — Nimbus then
 * refuses to even attempt verifying a token whose header declares a different {@code alg}, {@code
 * none} included, before it ever gets near signature checking. That is CV-09's exact scenario:
 * an {@code alg: none} token and an HS256 token signed with the public key (the classic
 * algorithm-confusion attack, meaningful only because the real system uses an asymmetric
 * algorithm) both fail at the same point, for the same reason, not two different code paths that
 * could drift apart.
 *
 * <p>No login/token-issuance endpoint exists in this service on purpose — SRS's own endpoint
 * table (line 381-387) never lists one; this is a pure OAuth2 resource server that verifies
 * tokens issued elsewhere, the standard resource-server/authorization-server split. Local/demo
 * token minting lives in test code and {@code scripts/}, never in {@code tributary-api} itself.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public JwtDecoder jwtDecoder(@Value("${tributary.security.jwt.public-key}") String publicKeyPem) {
    RSAPublicKey publicKey = parseRsaPublicKey(publicKeyPem);
    return NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
  }

  /**
   * T-700 / SRS 5.3's literal header list. {@code X-Content-Type-Options: nosniff} is Spring
   * Security's own default (kept implicit); the rest are set explicitly because none of them are.
   * CSP is specified with concrete directives even though this API returns only JSON and never
   * HTML — SRS 5.3's own reasoning: it stops a mistyped response from ever being interpreted as
   * executable content by a browser that receives it regardless of the intended content type.
   */
  @Bean
  public HostAllowlistFilter hostAllowlistFilter(@Value("${tributary.security.allowed-hosts}") String allowedHosts) {
    return new HostAllowlistFilter(splitCsv(allowedHosts));
  }

  @Bean
  public com.tributary.api.web.RequestLoggingFilter requestLoggingFilter() {
    return new com.tributary.api.web.RequestLoggingFilter();
  }

  @Bean
  public ActorCaptureFilter actorCaptureFilter() {
    return new ActorCaptureFilter();
  }

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      HostAllowlistFilter hostAllowlistFilter,
      com.tributary.api.web.RequestLoggingFilter requestLoggingFilter,
      ActorCaptureFilter actorCaptureFilter,
      @Value("${tributary.security.cors-allowed-origins:}") String corsAllowedOrigins,
      @Value("${tributary.openapi.export-enabled:false}") boolean openApiExportEnabled) throws Exception {
    // T-707: /v3/api-docs is reachable ONLY when this deployment was started specifically to
    // export the OpenAPI document (scripts/export-openapi.sh sets tributary.openapi.export-enabled).
    // Default is false, so a real deployment never carries a second public route — ADR-009's
    // "exactly one unauthenticated route" claim stays literally true outside the export script's
    // own throwaway, non-networked local run.
    org.springframework.security.authorization.AuthorizationManager<
            org.springframework.security.web.access.intercept.RequestAuthorizationContext>
        apiDocsAccess =
            (authentication, context) -> new AuthorizationDecision(openApiExportEnabled);

    http.csrf(csrf -> csrf.disable()) // stateless bearer-token API: no cookie/session to forge
        .cors(cors -> cors.configurationSource(corsConfigurationSource(corsAllowedOrigins)))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
        .addFilterBefore(hostAllowlistFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
        // Runs first of all: every request gets one log line, including ones HostAllowlistFilter
        // or authentication/authorization reject downstream — a wrapping filter (try/finally
        // around the rest of the chain) still sees the final response status either way.
        .addFilterBefore(requestLoggingFilter, HostAllowlistFilter.class)
        // Positioned AFTER JWT authentication resolves, BEFORE the authorization decision — see
        // ActorCaptureFilter's own Javadoc: this is the one point where the security context is
        // both populated (authentication has run) and not yet cleared, and it stashes the actor
        // into a request attribute that survives the later clearing, for requestLoggingFilter to
        // read back once it's outside the chain.
        .addFilterAfter(
            actorCaptureFilter,
            org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
                .class)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // ADR-009: the ONE unauthenticated route in the whole system, and only this exact one.
                    .requestMatchers(HttpMethod.GET, "/api/v1/records/*/verification")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/invoices")
                    .access(hasRole(Roles.OPERATOR))
                    .requestMatchers(HttpMethod.POST, "/api/v1/invoices/*/issuances")
                    .access(hasRole(Roles.OPERATOR))
                    .requestMatchers(HttpMethod.POST, "/api/v1/invoices/*/corrections")
                    .access(hasRole(Roles.OPERATOR))
                    .requestMatchers(HttpMethod.GET, "/api/v1/invoices/*/renderings/xrechnung")
                    .access(hasRole(Roles.OPERATOR))
                    .requestMatchers(HttpMethod.GET, "/api/v1/invoices/*")
                    .access(hasAnyRole(Roles.OPERATOR, Roles.AUDITOR))
                    .requestMatchers(HttpMethod.GET, "/api/v1/chains/*/verification")
                    .access(hasRole(Roles.AUDITOR))
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/subjects/*/personal-data")
                    .access(hasRole(Roles.ADMIN))
                    .requestMatchers(HttpMethod.GET, "/v3/api-docs/**")
                    .access(apiDocsAccess)
                    // SRS 9A / T-009: no route this table doesn't explicitly grant is reachable by
                    // any role — an endpoint added later without an explicit line here fails
                    // closed, not open.
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  /**
   * A single {@code role} claim, not a collection — T-604's own model has exactly one role per
   * actor (RF-007/SRS 5.3: "separación de funciones," never both issuance and erasure on one
   * identity), so there is nothing to gain from the built-in {@code JwtGrantedAuthoritiesConverter}
   * (designed for scope-style multi-valued claims) and a real cost in ambiguity if a token ever
   * carried more than one.
   */
  private static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
    return jwt -> {
      String role = jwt.getClaimAsString("role");
      List<GrantedAuthority> authorities =
          role == null ? List.of() : List.of(new SimpleGrantedAuthority(Roles.AUTHORITY_PREFIX + role));
      return new JwtAuthenticationToken(jwt, authorities);
    };
  }

  private static org.springframework.security.authorization.AuthorizationManager<
          org.springframework.security.web.access.intercept.RequestAuthorizationContext>
      hasRole(String role) {
    String authority = Roles.AUTHORITY_PREFIX + role;
    return (authentication, context) ->
        new AuthorizationDecision(
            authentication.get().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority)));
  }

  private static org.springframework.security.authorization.AuthorizationManager<
          org.springframework.security.web.access.intercept.RequestAuthorizationContext>
      hasAnyRole(String... roles) {
    List<String> authorities = List.of(roles).stream().map(r -> Roles.AUTHORITY_PREFIX + r).toList();
    return (authentication, context) ->
        new AuthorizationDecision(
            authentication.get().getAuthorities().stream().anyMatch(a -> authorities.contains(a.getAuthority())));
  }

  /**
   * SRS 5.3: "CORS: allowlist explícita, nunca comodín." Empty by default — ADR-006 declares this
   * project has no UI at all, so no browser origin needs cross-origin access unless a deployment
   * explicitly configures one; {@code CorsConfiguration} with an empty allowed-origins list
   * permits nothing, never falls back to {@code "*"}.
   */
  private static CorsConfigurationSource corsConfigurationSource(String allowedOriginsCsv) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.copyOf(splitCsv(allowedOriginsCsv)));
    configuration.setAllowedMethods(List.of("GET", "POST", "DELETE"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  private static Set<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet());
  }

  private static RSAPublicKey parseRsaPublicKey(String pem) {
    String normalized =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    byte[] decoded = Base64.getDecoder().decode(normalized);
    try {
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("tributary.security.jwt.public-key is not a valid RSA public key", e);
    }
  }
}
