package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1..#4 de la
 * Story 3.2. Mismo patrón que {@link AuthLoginIntegrationTest}: la Cuenta se
 * crea directamente vía {@link AccountRepository}/{@link PasswordHasher}
 * reales (no vía {@code POST /auth/register}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthLoginLockoutIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private AccountId persistAccount(String email, AccountStatus status, int failedAttempts, Instant lockedUntil) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), hashedPassword,
                status, Set.of(Role.USER), failedAttempts, lockedUntil, Instant.now());
        return accountRepository.save(account).id();
    }

    private void loginWithWrongPassword(String email) throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fifthConsecutiveWrongPasswordLocksTheAccount() throws Exception {
        AccountId accountId = persistAccount("fuerza-bruta@example.com", AccountStatus.ACTIVE, 0, null);

        for (int i = 0; i < 4; i++) {
            loginWithWrongPassword("fuerza-bruta@example.com");
        }
        Optional<Account> afterFourFailures = accountRepository.findById(accountId);
        assertThat(afterFourFailures).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(a.failedAttempts()).isEqualTo(4);
        });

        loginWithWrongPassword("fuerza-bruta@example.com");

        Optional<Account> afterFifthFailure = accountRepository.findById(accountId);
        assertThat(afterFifthFailure).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(AccountStatus.LOCKED);
            assertThat(a.lockedUntil()).isNotNull();
        });
    }

    @Test
    void lockedAccountRejectsEvenTheCorrectPasswordWith401() throws Exception {
        persistAccount("ya-bloqueada@example.com", AccountStatus.LOCKED, 5, Instant.now().plusSeconds(600));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ya-bloqueada@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredLockAutoUnlocksAndAcceptsTheCorrectPasswordAgain() throws Exception {
        AccountId accountId = persistAccount("desbloqueo-automatico@example.com", AccountStatus.LOCKED, 5,
                Instant.now().minusSeconds(1));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"desbloqueo-automatico@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isOk());

        Optional<Account> afterLogin = accountRepository.findById(accountId);
        assertThat(afterLogin).hasValueSatisfying(a -> {
            assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(a.failedAttempts()).isZero();
            assertThat(a.lockedUntil()).isNull();
        });
    }

    @Test
    void successfulLoginAfterSomeFailuresBelowThresholdResetsCounter() throws Exception {
        AccountId accountId = persistAccount("recupera-contador@example.com", AccountStatus.ACTIVE, 0, null);

        loginWithWrongPassword("recupera-contador@example.com");
        loginWithWrongPassword("recupera-contador@example.com");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"recupera-contador@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isOk());

        Optional<Account> afterLogin = accountRepository.findById(accountId);
        assertThat(afterLogin).hasValueSatisfying(a -> assertThat(a.failedAttempts()).isZero());
    }
}
