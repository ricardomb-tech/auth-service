package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración real contra PostgreSQL vía Testcontainers — Story 1.5, Task 3.
 * Cubre {@link RefreshTokenRepositoryAdapter#findByTokenHash}, {@code markUsedIfUnused}
 * y {@code revokeFamily}, incluida su naturaleza atómica/condicional (mismo
 * patrón que {@code AuthLoginIntegrationTest}, Story 1.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class RefreshTokenRepositoryAdapterTest {

    // Story 2.1: con webEnvironment=NONE, la autoconfiguración de OAuth2 Client
    // (condicionada a aplicación web) no crea ClientRegistrationRepository,
    // pero SecurityConfig (nuestra propia @Configuration) igual se carga y su
    // .oauth2Login() lo exige. Este test no ejercita seguridad HTTP alguna —
    // un mock basta para satisfacer el cableado.
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AccountRepository accountRepository;

    private AccountId persistAccount() {
        return persistAccount("titular@example.com");
    }

    private AccountId persistAccount(String email) {
        Account account = Account.reconstitute(AccountId.newId(), new Email(email),
                new HashedPassword("bcrypt-hash"), AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        return accountRepository.save(account).id();
    }

    private RefreshToken persistToken(AccountId accountId, UUID familyId) {
        RefreshToken.Issued issued = RefreshToken.issue(accountId, familyId, Duration.ofDays(7), java.time.Clock.systemUTC());
        return refreshTokenRepository.save(issued.token());
    }

    @Test
    void findByTokenHashReturnsTheTokenWhenItExists() {
        AccountId accountId = persistAccount();
        RefreshToken token = persistToken(accountId, UUID.randomUUID());

        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(token.tokenHash());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(token.id());
        assertThat(found.get().accountId()).isEqualTo(accountId);
        assertThat(found.get().familyId()).isEqualTo(token.familyId());
    }

    @Test
    void findByTokenHashReturnsEmptyWhenTheHashIsUnknown() {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash("nonexistent-hash");

        assertThat(found).isEmpty();
    }

    @Test
    void markUsedIfUnusedReturnsOneTheFirstTimeAndZeroTheSecondTime() {
        AccountId accountId = persistAccount();
        RefreshToken token = persistToken(accountId, UUID.randomUUID());
        Instant usedAt = Instant.now();

        int firstAttempt = refreshTokenRepository.markUsedIfUnused(token.id(), usedAt);
        int secondAttempt = refreshTokenRepository.markUsedIfUnused(token.id(), usedAt);

        assertThat(firstAttempt).isEqualTo(1);
        assertThat(secondAttempt).isEqualTo(0);
    }

    @Test
    void markUsedIfUnusedReturnsZeroWhenTheTokenIsAlreadyRevoked() {
        AccountId accountId = persistAccount();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = persistToken(accountId, familyId);
        refreshTokenRepository.revokeFamily(familyId, Instant.now());

        int affected = refreshTokenRepository.markUsedIfUnused(token.id(), Instant.now());

        assertThat(affected).isEqualTo(0);
    }

    @Test
    void revokeFamilyMarksRevokedAtOnEveryRowOfTheFamilyAndReturnsZeroWhenAlreadyRevoked() {
        AccountId accountId = persistAccount();
        UUID familyId = UUID.randomUUID();
        RefreshToken first = persistToken(accountId, familyId);
        RefreshToken second = persistToken(accountId, familyId);
        Instant revokedAt = Instant.now();

        int affected = refreshTokenRepository.revokeFamily(familyId, revokedAt);

        assertThat(affected).isEqualTo(2);
        assertThat(refreshTokenRepository.findByTokenHash(first.tokenHash()).orElseThrow().revokedAt()).isNotNull();
        assertThat(refreshTokenRepository.findByTokenHash(second.tokenHash()).orElseThrow().revokedAt()).isNotNull();

        int secondAttempt = refreshTokenRepository.revokeFamily(familyId, Instant.now());

        assertThat(secondAttempt).isEqualTo(0);
    }

    @Test
    void revokeAllForAccountRevokesEveryFamilyOfThatAccountButNotOtherAccounts() {
        AccountId accountId = persistAccount();
        UUID familyOne = UUID.randomUUID();
        UUID familyTwo = UUID.randomUUID();
        RefreshToken tokenFamilyOne = persistToken(accountId, familyOne);
        RefreshToken tokenFamilyTwo = persistToken(accountId, familyTwo);

        AccountId otherAccountId = persistAccount("otro-titular@example.com");
        RefreshToken otherAccountToken = persistToken(otherAccountId, UUID.randomUUID());

        Instant revokedAt = Instant.now();
        int affected = refreshTokenRepository.revokeAllForAccount(accountId, revokedAt);

        assertThat(affected).isEqualTo(2);
        assertThat(refreshTokenRepository.findByTokenHash(tokenFamilyOne.tokenHash()).orElseThrow().revokedAt()).isNotNull();
        assertThat(refreshTokenRepository.findByTokenHash(tokenFamilyTwo.tokenHash()).orElseThrow().revokedAt()).isNotNull();
        assertThat(refreshTokenRepository.findByTokenHash(otherAccountToken.tokenHash()).orElseThrow().revokedAt()).isNull();
    }
}
