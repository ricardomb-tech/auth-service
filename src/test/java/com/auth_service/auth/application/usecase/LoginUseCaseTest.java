package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.LockoutProperties;
import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final LockoutProperties lockoutProperties = new LockoutProperties(5, Duration.ofMinutes(15));
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private final LoginUseCase useCase =
            new LoginUseCase(accountRepository, passwordHasher, tokenIssuer, lockoutProperties, eventPublisher, clock);

    private Account activeAccount() {
        return Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
    }

    private Account activeAccountWithFailedAttempts(int failedAttempts) {
        return Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), failedAttempts, null, Instant.now());
    }

    @Test
    void validCredentialsOnActiveAccountIssuesTokens() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access", "refresh", 900L);
        when(tokenIssuer.issue(account)).thenReturn(issuedTokens);

        TokenIssuer.IssuedTokens result = useCase.login(new LoginCommand("titular@example.com", "Str0ngPass"));

        assertThat(result).isEqualTo(issuedTokens);
    }

    @Test
    void nonExistentEmailThrowsAuthenticationFailedWithoutIssuingTokens() {
        when(accountRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login(new LoginCommand("nadie@example.com", "Str0ngPass")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void wrongPasswordThrowsAuthenticationFailedWithoutIssuingTokens() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "WrongPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "LOCKED", "DISABLED"})
    void nonActiveAccountWithCorrectPasswordThrowsAuthenticationFailedWithoutIssuingTokens(AccountStatus status) {
        Account account = Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                status, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "Str0ngPass")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void federatedOnlyAccountWithNullPasswordHashThrowsAuthenticationFailedWithoutCallingPasswordHasher() {
        // Story 2.1: una Cuenta creada solo vía Google no tiene password_hash.
        // Con el PasswordHasher real (BCryptPasswordHasher), matches(raw, null)
        // lanza NullPointerException -> 500, una fuga de enumeración. El mock
        // de este test no reproduce esa NPE (un mock no ejecuta lógica real),
        // así que la aserción correcta es que el guard impide llegar siquiera
        // a invocar matches() cuando passwordHash es null.
        Account account = Account.reconstitute(AccountId.newId(), new Email("federado@example.com"), null,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.login(new LoginCommand("federado@example.com", "CualquierPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(passwordHasher, never()).matches(any(), any());
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void federatedOnlyAccountWithNullPasswordHashDoesNotCountAsFailedAttempt() {
        // Review Findings (Story 4.1): sin contraseña local que adivinar, un
        // intento contra una Cuenta solo-federada no debe consumir el umbral
        // de bloqueo — de lo contrario un atacante podría bloquear
        // indefinidamente una Cuenta cuyo titular nunca usa /auth/login.
        Account account = Account.reconstitute(AccountId.newId(), new Email("federado@example.com"), null,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.login(new LoginCommand("federado@example.com", "CualquierPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(account.failedAttempts()).isZero();
        verify(accountRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void wrongPasswordIncrementsFailedAttemptsAndPersists() {
        Account account = activeAccountWithFailedAttempts(0);
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "WrongPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(account.failedAttempts()).isEqualTo(1);
        verify(accountRepository).save(argThat(a -> a.failedAttempts() == 1));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void fifthConsecutiveFailureLocksAccountAndPublishesEmailEvent() {
        Account account = activeAccountWithFailedAttempts(4);
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "WrongPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(account.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.lockedUntil()).isEqualTo(Instant.now(clock).plus(Duration.ofMinutes(15)));
        verify(eventPublisher).publishEvent(any(AccountLockedEmailRequested.class));
    }

    @Test
    void sixthAttemptOnAlreadyLockedAccountDoesNotPublishAgainNorSave() {
        Account account = Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, Instant.now(clock).plus(Duration.ofMinutes(10)), Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "Str0ngPass")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(eventPublisher, never()).publishEvent(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void successfulLoginAfterPriorFailuresResetsCounter() {
        Account account = activeAccountWithFailedAttempts(3);
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        when(tokenIssuer.issue(account)).thenReturn(new TokenIssuer.IssuedTokens("access", "refresh", 900L));

        useCase.login(new LoginCommand("titular@example.com", "Str0ngPass"));

        assertThat(account.failedAttempts()).isZero();
        verify(accountRepository).save(argThat(a -> a.failedAttempts() == 0));
    }

    @Test
    void successfulLoginWithNoPriorFailuresDoesNotWriteToRepository() {
        Account account = activeAccountWithFailedAttempts(0);
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access", "refresh", 900L);
        when(tokenIssuer.issue(account)).thenReturn(issuedTokens);

        TokenIssuer.IssuedTokens result = useCase.login(new LoginCommand("titular@example.com", "Str0ngPass"));

        assertThat(result).isEqualTo(issuedTokens);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiredLockAutoUnlocksBeforeEvaluatingCredentials() {
        Account account = Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, Instant.now(clock).minusSeconds(1), Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        when(tokenIssuer.issue(account)).thenReturn(new TokenIssuer.IssuedTokens("access", "refresh", 900L));

        TokenIssuer.IssuedTokens result = useCase.login(new LoginCommand("titular@example.com", "Str0ngPass"));

        assertThat(result).isNotNull();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.failedAttempts()).isZero();
        // Una vez por el auto-desbloqueo (unlockIfExpired) — el reset de
        // recordSuccessfulLogin no dispara un segundo save() porque
        // failedAttempts ya quedó en 0 tras el desbloqueo.
        verify(accountRepository, times(1)).save(any());
    }

    @Test
    void expiredLockAutoUnlocksButWrongPasswordStillFails() {
        Account account = Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, Instant.now(clock).minusSeconds(1), Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "WrongPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.failedAttempts()).isEqualTo(1);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
