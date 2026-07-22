package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.command.ResetPasswordCommand;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResetPasswordUseCaseTest {

    private final VerificationTokenRepository verificationTokenRepository = mock(VerificationTokenRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private ResetPasswordUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResetPasswordUseCase(verificationTokenRepository, accountRepository, refreshTokenRepository, passwordHasher, clock);
    }

    @Test
    void validTokenAndPasswordUpdatesAccountConsumesTokenAndRevokesAllFamilies() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("old-hash"));
        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));
        when(verificationTokenRepository.consumeIfActive(eq(issued.token().tokenHash()), any(Instant.class))).thenReturn(1);
        when(accountRepository.findById(account.id())).thenReturn(Optional.of(account));
        HashedPassword newHash = new HashedPassword("new-hash");
        when(passwordHasher.hash(any(RawPassword.class))).thenReturn(newHash);

        useCase.resetPassword(new ResetPasswordCommand(issued.rawToken(), "NuevaPass1"));

        assertThat(account.passwordHash()).isEqualTo(newHash);
        verify(accountRepository).save(account);
        verify(verificationTokenRepository).consumeIfActive(eq(issued.token().tokenHash()), any(Instant.class));
        verify(refreshTokenRepository).revokeAllForAccount(eq(account.id()), any(Instant.class));
    }

    @Test
    void concurrentConsumeLosesRaceThrowsAndTouchesNothingElse() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("old-hash"));
        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));
        when(passwordHasher.hash(any(RawPassword.class))).thenReturn(new HashedPassword("new-hash"));
        // Otra petición concurrente ganó la carrera y ya consumió el token en BD justo antes de este UPDATE atómico.
        when(verificationTokenRepository.consumeIfActive(eq(issued.token().tokenHash()), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> useCase.resetPassword(new ResetPasswordCommand(issued.rawToken(), "NuevaPass1")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void nonExistentTokenThrowsAndTouchesNothing() {
        when(verificationTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.resetPassword(new ResetPasswordCommand("bogus-raw-token", "NuevaPass1")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(verificationTokenRepository, never()).consumeIfActive(any(), any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void wrongPurposeTokenThrowsAndTouchesNothing() {
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));

        assertThatThrownBy(() -> useCase.resetPassword(new ResetPasswordCommand(issued.rawToken(), "NuevaPass1")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(verificationTokenRepository, never()).consumeIfActive(any(), any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void alreadyConsumedTokenThrowsAndTouchesNothing() {
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        VerificationToken alreadyConsumed = issued.token().consume(clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(alreadyConsumed));

        assertThatThrownBy(() -> useCase.resetPassword(new ResetPasswordCommand(issued.rawToken(), "NuevaPass1")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(verificationTokenRepository, never()).consumeIfActive(any(), any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }

    @Test
    void invalidNewPasswordThrowsWithoutConsumingTheToken() {
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));

        // "short" incumple la política mínima (≥8 caracteres) — RawPassword la rechaza.
        assertThatThrownBy(() -> useCase.resetPassword(new ResetPasswordCommand(issued.rawToken(), "short")))
                .isInstanceOf(DomainValidationException.class);

        // El token nunca se consumió: sigue disponible para un reintento válido.
        verify(verificationTokenRepository, never()).consumeIfActive(any(), any());
        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
    }
}
