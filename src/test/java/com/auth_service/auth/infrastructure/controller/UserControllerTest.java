package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.command.GetOwnProfileCommand;
import com.auth_service.auth.application.usecase.GetOwnProfileUseCase;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.config.SecurityConfig;
import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.FederatedIdentity;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
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
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test con {@link GetOwnProfileUseCase} mockeado — {@code @Import(SecurityConfig.class)}:
 * sin esto, {@code @WebMvcTest} cae al deny-all por defecto de Spring
 * Security (mismo ajuste que {@code AuthControllerTest}/{@code SecurityConfigTest}).
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile"
})
class UserControllerTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

    @Autowired
    private MockMvc mockMvc;

    // Story 2.1: SecurityConfig ahora depende de estos colaboradores de
    // OAuth2 — mockeados aquí para no arrastrar FederatedLoginUseCase, ajeno
    // a lo que este slice test verifica (los endpoints de UserController).
    @MockitoBean
    private CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private GitHubOAuth2UserService gitHubOAuth2UserService;

    @MockitoBean
    private GetOwnProfileUseCase getOwnProfileUseCase;

    private String tokenFor(String subject) {
        return tokenFor(subject, Instant.now().plus(Duration.ofMinutes(15)));
    }

    private String tokenFor(String subject, Instant expiration) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("email", "titular@example.com")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(expiration.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiration))
                .issuer("auth-service")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private Account accountWith(AccountId id) {
        HashedPassword hashedPassword = new HashedPassword("$2a$10$irrelevantIrrelevantIrrelevantIrrelevantIrrelevantIrre");
        return Account.reconstitute(id, new Email("titular@example.com"), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void validTokenReturns200WithProfileFieldsAndInvokesUseCaseWithSubject() throws Exception {
        AccountId accountId = AccountId.newId();
        Account account = accountWith(accountId);
        when(getOwnProfileUseCase.getOwnProfile(eq(new GetOwnProfileCommand(accountId.value().toString()))))
                .thenReturn(new GetOwnProfileUseCase.OwnProfile(account, List.of()));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(accountId.value().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.value().toString()))
                .andExpect(jsonPath("$.email").value("titular@example.com"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.federatedIdentities").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-07-01T00:00:00Z"));

        verify(getOwnProfileUseCase).getOwnProfile(eq(new GetOwnProfileCommand(accountId.value().toString())));
    }

    @Test
    void accountWithLinkedGoogleIdentityReturnsItInFederatedIdentities() throws Exception {
        AccountId accountId = AccountId.newId();
        Account account = accountWith(accountId);
        FederatedIdentity identity = FederatedIdentity.reconstitute(
                java.util.UUID.randomUUID(), accountId, FederatedProvider.GOOGLE, "google-sub-1", Instant.now());
        when(getOwnProfileUseCase.getOwnProfile(eq(new GetOwnProfileCommand(accountId.value().toString()))))
                .thenReturn(new GetOwnProfileUseCase.OwnProfile(account, List.of(identity)));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(accountId.value().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.federatedIdentities", containsInAnyOrder("google")));
    }

    @Test
    void missingAuthorizationHeaderReturns401ProblemJsonAndNeverInvokesUseCase() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required."));

        verify(getOwnProfileUseCase, never()).getOwnProfile(any());
    }

    @Test
    void expiredTokenReturns401ProblemJsonAndNeverInvokesUseCase() throws Exception {
        // AC #2: "con un token inválido o expirado" — token bien firmado pero
        // vencido; ejercita la rama ExpiredJwtException del filtro contra esta ruta.
        String expiredToken = tokenFor(AccountId.newId().value().toString(),
                Instant.now().minus(Duration.ofMinutes(30)));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required."));

        verify(getOwnProfileUseCase, never()).getOwnProfile(any());
    }

    @Test
    void useCaseThrowingAccountNotFoundExceptionReturns401ProblemJsonNot500() throws Exception {
        String subject = AccountId.newId().value().toString();
        when(getOwnProfileUseCase.getOwnProfile(eq(new GetOwnProfileCommand(subject))))
                .thenThrow(new AccountNotFoundException("Ninguna Cuenta corresponde al claim sub del Access Token."));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenFor(subject)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // La razón de ser de AccountNotFoundException (AC #3): el detail es
                // exactamente el del authenticationEntryPoint — un sub sin Cuenta es
                // indistinguible de un token ausente, y nunca filtra ex.getMessage().
                .andExpect(jsonPath("$.detail").value("Authentication required."));
    }
}
