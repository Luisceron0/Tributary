package com.tributary.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-707: metadata plus the one thing springdoc never infers on its own from a custom {@code
 * AuthorizationManager}-based {@code SecurityFilterChain} — the bearer JWT scheme. Declared once
 * here and required by default on every operation, then explicitly removed from the one route
 * that doesn't need it ({@link #publicVerificationEndpointCustomizer}) — ADR-009's "exactly one
 * unauthenticated route" should be readable in the generated contract, not just enforced by
 * {@code SecurityConfig} and left undocumented.
 */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  public OpenAPI tributaryOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Tributary API")
                .version("0.1.0")
                .description(
                    "Multi-regime e-invoicing reference implementation (CO/ES/DE). Not certified"
                        + " under any fiscal regime — see ADR-005 and the README's scope-limits"
                        + " section. No login endpoint: this is a pure OAuth2 resource server,"
                        + " tokens are issued elsewhere."))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }

  /** ADR-009: the one route with no auth requirement, made explicit in the generated contract too. */
  @Bean
  public OperationCustomizer publicVerificationEndpointCustomizer() {
    return (operation, handlerMethod) -> {
      if (handlerMethod.getBeanType().getSimpleName().equals("RecordController")) {
        operation.setSecurity(java.util.List.of());
      }
      return operation;
    };
  }
}
