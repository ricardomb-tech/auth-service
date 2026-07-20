package com.auth_service.auth.config;

import com.auth_service.auth.infrastructure.adapters.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.auth_service.auth.infrastructure.adapters.oauth.GitHubOAuth2UserService;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationFailureHandler;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationSuccessHandler;
import com.auth_service.auth.infrastructure.adapters.security.JwtAuthenticationFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test (sin base de datos): confirma que la seguridad deny-all
 * (AD-11) y el cuerpo Problem Details (AD-8) funcionan a nivel de filtro,
 * independientemente de qué controllers reales existan. AC #3 de la Story
 * 1.1; AC #4 de la Story 1.4 (el filtro JWT autentica cuando el token es
 * válido y no cambia el default cuando no lo es).
 *
 * <p>{@code @WebMvcTest(controllers = NoOpTestController.class)} acota
 * deliberadamente el slice a un controller vacío — sin esto, Spring
 * escanearía también {@code AuthController} (Story 1.2) y arrastraría toda
 * su cadena de dependencias (casos de uso, repositorios), que este test no
 * necesita ni quiere levantar. {@code NoOpTestController} vive en su propio
 * archivo (no como clase anidada aquí): un {@code @RestController} anidado
 * estático dentro de la propia clase de test **no** es detectado de forma
 * fiable por el escaneo de {@code @WebMvcTest(controllers=...)} en esta
 * versión de Spring Boot — bug real encontrado al añadir el primer test que
 * de verdad ejercitaba una ruta mapeada (los tests previos de esta clase
 * solo dependían del deny-all, nunca de que el controller existiera).</p>
 */
@WebMvcTest(controllers = NoOpTestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        // oauth2Login() exige un ClientRegistrationRepository en el contexto
        // para construir la SecurityFilterChain — valores ficticios, este
        // test nunca llama a Google de verdad.
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile",
        "spring.security.oauth2.client.registration.github.client-id=test-github-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-github-client-secret",
        "spring.security.oauth2.client.registration.github.scope=read:user,user:email"
})
class SecurityConfigTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

    @Autowired
    private MockMvc mockMvc;

    // Story 2.1: SecurityConfig ahora depende de estos colaboradores de OAuth2
    // (cadena completa hasta FederatedLoginUseCase/AccountRepository) — este
    // slice test solo verifica el deny-all, no el flujo OAuth2 en sí, así que
    // se mockean para no arrastrar dependencias irrelevantes a este test.
    @MockitoBean
    private CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private GitHubOAuth2UserService gitHubOAuth2UserService;

    @Test
    void unauthenticatedRequestToNonPublicRouteReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /**
     * Verifica únicamente que el filtro de seguridad no bloquea la ruta
     * pública — NO que springdoc sirva el recurso correctamente (eso lo
     * cubre {@code AuthServiceApplicationTests.swaggerUiIsServedAndPublic}
     * con el contexto completo). Un 404 aquí es válido: en este slice test
     * sin el auto-configure completo de springdoc no hay garantía de que
     * el recurso exista; lo único que este test no debe tolerar es 401/403,
     * que indicarían que `PUBLIC_ENDPOINTS` dejó de cubrir la ruta.
     */
    @Test
    void swaggerUiPathIsNotBlockedBySecurityFilter() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
                });
    }

    /**
     * Verifica únicamente que el filtro de seguridad no bloquea la ruta de
     * inicio del flujo OAuth2 (Story 2.1) — no que la redirección a Google
     * ocurra correctamente (eso requiere la configuración completa del
     * {@code ClientRegistrationRepository}, cubierto por el test de
     * integración de la Story 2.1). Lo único que este test no debe tolerar
     * es 401/403, que indicarían que `/oauth2/**` dejó de ser público.
     */
    @Test
    void oauth2AuthorizationPathIsNotBlockedBySecurityFilter() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
                });
    }

    /**
     * Mismo criterio que el test de Google (Story 2.1), ahora para GitHub
     * (Story 2.2) — `/oauth2/**` ya era agnóstico del `registrationId`.
     */
    @Test
    void gitHubOauth2AuthorizationPathIsNotBlockedBySecurityFilter() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/github"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
                });
    }

    @Test
    void validAccessTokenAuthenticatesAProtectedRoute() throws Exception {
        mockMvc.perform(get("/__test/protected").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenOnProtectedRouteReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/__test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private String validToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("account-123")
                .claim("email", "titular@example.com")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .issuer("auth-service")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
