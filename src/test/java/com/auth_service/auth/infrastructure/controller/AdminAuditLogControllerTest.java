package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.QueryAuditLogUseCase;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.config.SecurityConfig;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.domain.model.AuditResult;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test con {@link QueryAuditLogUseCase} mockeado — mismo patrón que
 * {@code AdminControllerTest} (Story 4.2).
 */
@WebMvcTest(controllers = AdminAuditLogController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile"
})
class AdminAuditLogControllerTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;

    @MockitoBean
    private OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

    @MockitoBean
    private OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

    @MockitoBean
    private GitHubOAuth2UserService gitHubOAuth2UserService;

    @MockitoBean
    private QueryAuditLogUseCase queryAuditLogUseCase;

    private String tokenFor(String subject, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant expiration = Instant.now().plus(Duration.ofMinutes(15));
        return Jwts.builder()
                .subject(subject)
                .claim("email", "titular@example.com")
                .claim("roles", roles)
                .issuedAt(Date.from(expiration.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiration))
                .issuer("auth-service")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void searchWithAdminRoleReturns200AndEntries() throws Exception {
        AuditLogEntry entry = AuditLogEntry.create(AccountId.newId(), AccountId.newId(), AdminAction.DISABLE_ACCOUNT,
                AuditResult.SUCCESS, Instant.parse("2026-07-01T00:00:00Z"));
        when(queryAuditLogUseCase.search(any(), any(), any())).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("DISABLE_ACCOUNT"))
                .andExpect(jsonPath("$[0].result").value("SUCCESS"));
    }

    @Test
    void searchWithUserRoleReturns403ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("USER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied."));

        verify(queryAuditLogUseCase, never()).search(any(), any(), any());
    }

    @Test
    void searchWithInvalidAccountIdReturns400ProblemJson() throws Exception {
        when(queryAuditLogUseCase.search(any(), any(), any()))
                .thenThrow(new DomainValidationException("accountId no es un UUID válido."));

        mockMvc.perform(get("/api/v1/admin/audit-log?accountId=no-es-un-uuid")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void missingAuthorizationHeaderReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));

        verify(queryAuditLogUseCase, never()).search(any(), any(), any());
    }
}
