package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.ManageAccountUseCase;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.config.SecurityConfig;
import com.auth_service.auth.domain.exception.SelfManagementNotAllowedException;
import com.auth_service.auth.domain.exception.TargetAccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test con {@link ManageAccountUseCase} mockeado — mismo patrón que
 * {@code UserControllerTest} ({@code @Import(SecurityConfig.class)} para no
 * caer al deny-all sin las reglas de {@code @PreAuthorize}).
 */
@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile"
})
class AdminControllerTest {

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
    private ManageAccountUseCase manageAccountUseCase;

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

    private Account accountWith(AccountId id) {
        HashedPassword hashedPassword = new HashedPassword("$2a$10$irrelevantIrrelevantIrrelevantIrrelevantIrrelevantIrre");
        return Account.reconstitute(id, new Email("titular@example.com"), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void listWithAdminRoleReturns200AndPagedBody() throws Exception {
        Account account = accountWith(AccountId.newId());
        when(manageAccountUseCase.listAccounts(0, 20))
                .thenReturn(new ManageAccountUseCase.AccountPage(List.of(account), 0, 20, 1L));

        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].email").value("titular@example.com"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listWithUserRoleReturns403ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("USER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied."));

        verify(manageAccountUseCase, never()).listAccounts(anyInt(), anyInt());
    }

    @Test
    void detailWithAdminRoleReturns200() throws Exception {
        AccountId targetId = AccountId.newId();
        when(manageAccountUseCase.getAccountDetail(eq(targetId.value().toString()))).thenReturn(accountWith(targetId));

        mockMvc.perform(get("/api/v1/admin/accounts/" + targetId.value())
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId.value().toString()));
    }

    @Test
    void detailWithUnknownIdReturns404ProblemJson() throws Exception {
        when(manageAccountUseCase.getAccountDetail(any())).thenThrow(new TargetAccountNotFoundException("Ninguna Cuenta corresponde al id indicado."));

        mockMvc.perform(get("/api/v1/admin/accounts/" + AccountId.newId().value())
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void disableReturns200WithUpdatedAccount() throws Exception {
        AccountId targetId = AccountId.newId();
        Account disabled = Account.reconstitute(targetId, new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.DISABLED, Set.of(Role.USER), 0, null, Instant.parse("2026-07-01T00:00:00Z"));
        when(manageAccountUseCase.disableAccount(any(), eq(targetId.value().toString()))).thenReturn(disabled);

        mockMvc.perform(post("/api/v1/admin/accounts/" + targetId.value() + "/disable")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void disableOnSelfReturns409ProblemJson() throws Exception {
        when(manageAccountUseCase.disableAccount(any(), any()))
                .thenThrow(new SelfManagementNotAllowedException("Un Administrador no puede desactivarse a sí mismo."));

        mockMvc.perform(post("/api/v1/admin/accounts/" + AccountId.newId().value() + "/disable")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void updateRolesReturns200WithUpdatedAccount() throws Exception {
        AccountId targetId = AccountId.newId();
        Account updated = Account.reconstitute(targetId, new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.ADMIN, Role.USER), 0, null, Instant.parse("2026-07-01T00:00:00Z"));
        when(manageAccountUseCase.updateRoles(any(), eq(targetId.value().toString()), any())).thenReturn(updated);

        mockMvc.perform(put("/api/v1/admin/accounts/" + targetId.value() + "/roles")
                        .header("Authorization", "Bearer " + tokenFor(AccountId.newId().value().toString(), List.of("ADMIN", "USER")))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\",\"USER\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingAuthorizationHeaderReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/admin/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON));

        verify(manageAccountUseCase, never()).listAccounts(anyInt(), anyInt());
    }
}
