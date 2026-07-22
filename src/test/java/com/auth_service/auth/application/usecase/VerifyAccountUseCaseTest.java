package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.command.VerifyCommand;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifyAccountUseCaseTest {

    private final VerificationTokenRepository verificationTokenRepository = mock(VerificationTokenRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private VerifyAccountUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new VerifyAccountUseCase(verificationTokenRepository, accountRepository, clock);
    }

    @Test
    void validTokenActivatesAccountAndConsumesToken() {
        Account account = Account.register(new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"));
        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));
        when(accountRepository.findById(account.id())).thenReturn(Optional.of(account));

        useCase.verify(new VerifyCommand(issued.rawToken()));

        assertThat(account.status().name()).isEqualTo("ACTIVE");
        verify(accountRepository).save(account);
        verify(verificationTokenRepository).save(any(VerificationToken.class));
    }

    @Test
    void nonExistentTokenThrowsAndTouchesNoAccount() {
        when(verificationTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.verify(new VerifyCommand("bogus-raw-token")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void wrongPurposeTokenThrowsAndTouchesNoAccount() {
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));

        assertThatThrownBy(() -> useCase.verify(new VerifyCommand(issued.rawToken())))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
        verify(verificationTokenRepository, never()).save(any());
    }

    @Test
    void alreadyConsumedTokenThrowsAndNeverActivatesAccount() {
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        VerificationToken alreadyConsumed = issued.token().consume(clock);
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(alreadyConsumed));

        assertThatThrownBy(() -> useCase.verify(new VerifyCommand(issued.rawToken())))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void expiredTokenThrowsAndNeverActivatesAccount() {
        Clock issuedAtClock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        AccountId accountId = AccountId.newId();
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(1), issuedAtClock);
        // El caso de uso usa `clock` (2026-07-02T00:00:00Z), muy posterior a la expiración de 1h.
        when(verificationTokenRepository.findByTokenHash(issued.token().tokenHash())).thenReturn(Optional.of(issued.token()));

        assertThatThrownBy(() -> useCase.verify(new VerifyCommand(issued.rawToken())))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).findById(any());
        verify(accountRepository, never()).save(any());
    }
}
