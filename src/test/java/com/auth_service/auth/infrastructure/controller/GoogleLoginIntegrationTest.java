package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.command.FederatedLoginCommand;
import com.auth_service.auth.application.usecase.FederatedLoginUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.FederatedIdentityRepository;
import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1, #2 de la
 * Story 2.1. No es posible ni deseable invocar la API real de Google en
 * tests (el intercambio código→token y la obtención del {@code OidcUser} son
 * responsabilidad ya probada de Spring Security) — el valor de esta
 * integración está en ejercitar {@link FederatedLoginUseCase} contra
 * PostgreSQL real (simulando lo que el success handler ya habría extraído
 * del principal OIDC) y en confirmar que {@code /oauth2/**} quedó público.
 * Web environment por defecto (MOCK, no RANDOM_PORT): {@code MockMvc} no
 * necesita un puerto real — ver ítem diferido de la Story 1.7 sobre este
 * mismo patrón en otros tests de integración.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile",
        "auth.oauth2.success-redirect-uri=https://frontend.example.com/success",
        "auth.oauth2.failure-redirect-uri=https://frontend.example.com/failure"
})
class GoogleLoginIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FederatedLoginUseCase federatedLoginUseCase;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private FederatedIdentityRepository federatedIdentityRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private OAuth2ExchangeCodeStore exchangeCodeStore;

    @Test
    void exchangeCodeIssuedByStoreCanBeRedeemedOnceViaTheRealEndpoint() throws Exception {
        // Revisión de seguridad de la Story 2.1: los tokens ya no viajan en la
        // URL de redirección — el frontend los obtiene canjeando un código de
        // un solo uso vía POST. Aquí se emite el código directamente contra el
        // bean real (lo que OAuth2AuthenticationSuccessHandler haría tras un
        // login federado exitoso) y se ejercita el endpoint real de canje.
        String code = exchangeCodeStore.issue(
                new OAuth2ExchangeCodeStore.IssuedTokens("access-abc", "refresh-xyz", 900L));

        mockMvc.perform(post("/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-abc"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-xyz"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));

        mockMvc.perform(post("/auth/oauth2/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unauthenticatedAuthorizationRequestRedirectsToGoogleNeverReturns401() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("accounts.google.com")));
    }

    @Test
    void newVisitorLoginPersistsActiveAccountAndFederatedIdentityAndIssuesUsableAccessToken() throws Exception {
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-new", "nuevovisitante@example.com", true);

        TokenIssuer.IssuedTokens tokens = federatedLoginUseCase.login(command);

        Optional<Account> persisted = accountRepository.findByEmail(new Email("nuevovisitante@example.com"));
        assertThat(persisted).isPresent();
        assertThat(persisted.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(persisted.get().passwordHash()).isNull();
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-new"))
                .isPresent();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void existingLocalAccountGetsLinkedWithoutDuplicatingTheAccountRow() throws Exception {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account localAccount = Account.reconstitute(AccountId.newId(), new Email("yaexiste@example.com"), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        accountRepository.save(localAccount);

        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-link", "yaexiste@example.com", true);
        federatedLoginUseCase.login(command);

        Optional<Account> afterLink = accountRepository.findByEmail(new Email("yaexiste@example.com"));
        assertThat(afterLink).isPresent();
        assertThat(afterLink.get().id()).isEqualTo(localAccount.id());
        assertThat(afterLink.get().passwordHash()).isNotNull();
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-link"))
                .hasValueSatisfying(identity -> assertThat(identity.accountId()).isEqualTo(localAccount.id()));
    }
}
