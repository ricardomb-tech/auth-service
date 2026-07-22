package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.command.FederatedLoginCommand;
import com.auth_service.auth.application.usecase.FederatedLoginUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.FederatedIdentityRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1, #2, #3 de la
 * Story 2.2. Igual que {@link GoogleLoginIntegrationTest} (Story 2.1), no es
 * posible ni deseable invocar la API real de GitHub en tests (el intercambio
 * código→token y la llamada a {@code /user/emails} de
 * {@code GitHubOAuth2UserService} son responsabilidad ya cubierta por sus
 * propios tests unitarios) — el valor de esta integración está en ejercitar
 * {@link FederatedLoginUseCase} contra PostgreSQL real (simulando lo que el
 * success handler + {@code GitHubOAuth2UserService} ya habrían extraído) y en
 * confirmar que {@code /oauth2/authorization/github} quedó público.
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
        "spring.security.oauth2.client.registration.github.client-id=test-github-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-github-client-secret",
        "spring.security.oauth2.client.registration.github.scope=read:user,user:email",
        "auth.oauth2.success-redirect-uri=https://frontend.example.com/success",
        "auth.oauth2.failure-redirect-uri=https://frontend.example.com/failure"
})
class GitHubLoginIntegrationTest {

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

    @Test
    void unauthenticatedAuthorizationRequestRedirectsToGitHubNeverReturns401() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("github.com/login/oauth/authorize")));
    }

    @Test
    void newVisitorLoginPersistsActiveAccountAndFederatedIdentityAndIssuesUsableAccessToken() throws Exception {
        FederatedLoginCommand command = new FederatedLoginCommand("github", "github-id-new", "nuevovisitante@example.com", true);

        TokenIssuer.IssuedTokens tokens = federatedLoginUseCase.login(command);

        Optional<Account> persisted = accountRepository.findByEmail(new Email("nuevovisitante@example.com"));
        assertThat(persisted).isPresent();
        assertThat(persisted.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(persisted.get().passwordHash()).isNull();
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GITHUB, "github-id-new"))
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

        FederatedLoginCommand command = new FederatedLoginCommand("github", "github-id-link", "yaexiste@example.com", true);
        federatedLoginUseCase.login(command);

        Optional<Account> afterLink = accountRepository.findByEmail(new Email("yaexiste@example.com"));
        assertThat(afterLink).isPresent();
        assertThat(afterLink.get().id()).isEqualTo(localAccount.id());
        assertThat(afterLink.get().passwordHash()).isNotNull();
        assertThat(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GITHUB, "github-id-link"))
                .hasValueSatisfying(identity -> assertThat(identity.accountId()).isEqualTo(localAccount.id()));
    }

    @Test
    void repeatedGitHubLoginWithSameProviderUserIdDoesNotDuplicateTheFederatedIdentityRow() throws Exception {
        // AC #2: "no se crea una segunda fila en federated_identities para el
        // mismo (provider, provider_user_id)" — caso GitHub-a-GitHub, no solo
        // el vínculo inicial con una cuenta local o Google.
        FederatedLoginCommand command = new FederatedLoginCommand("github", "github-id-repeat", "repetido@example.com", true);

        federatedLoginUseCase.login(command);
        Account afterFirstLogin = accountRepository.findByEmail(new Email("repetido@example.com")).orElseThrow();

        federatedLoginUseCase.login(command);

        Optional<Account> afterSecondLogin = accountRepository.findByEmail(new Email("repetido@example.com"));
        assertThat(afterSecondLogin).isPresent();
        assertThat(afterSecondLogin.get().id()).isEqualTo(afterFirstLogin.id());
        assertThat(federatedIdentityRepository.findByAccountId(afterFirstLogin.id()))
                .filteredOn(identity -> identity.provider() == FederatedProvider.GITHUB)
                .hasSize(1);
    }

    @Test
    void accountAlreadyLinkedToGoogleGetsBothFederatedIdentitiesWithoutDuplicatingTheAccountRow() throws Exception {
        // AC #2 de esta historia cubre explícitamente "Cuenta local o
        // federada-Google existente" — no solo Cuentas con password local.
        FederatedLoginCommand googleCommand = new FederatedLoginCommand("google", "google-id-both", "ambosproveedores@example.com", true);
        federatedLoginUseCase.login(googleCommand);
        Account afterGoogleLink = accountRepository.findByEmail(new Email("ambosproveedores@example.com")).orElseThrow();

        FederatedLoginCommand githubCommand = new FederatedLoginCommand("github", "github-id-both", "ambosproveedores@example.com", true);
        federatedLoginUseCase.login(githubCommand);

        Optional<Account> afterBothLinks = accountRepository.findByEmail(new Email("ambosproveedores@example.com"));
        assertThat(afterBothLinks).isPresent();
        assertThat(afterBothLinks.get().id()).isEqualTo(afterGoogleLink.id());
        assertThat(federatedIdentityRepository.findByAccountId(afterGoogleLink.id()))
                .extracting(identity -> identity.provider())
                .containsExactlyInAnyOrder(FederatedProvider.GOOGLE, FederatedProvider.GITHUB);
    }
}
