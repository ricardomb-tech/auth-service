package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestPasswordResetUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final VerificationTokenRepository verificationTokenRepository = mock(VerificationTokenRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private RequestPasswordResetUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RequestPasswordResetUseCase(accountRepository, verificationTokenRepository,
                new AuthTokenProperties(Duration.ofHours(24), Duration.ofDays(7), Duration.ofHours(1)), eventPublisher, clock);
    }

    @Test
    void existingAccountInvalidatesOldTokensIssuesNewOneAndPublishesEvent() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("bcrypt-hash"));
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        useCase.requestReset(new RequestPasswordResetCommand("titular@example.com"));

        verify(verificationTokenRepository).invalidateActiveTokens(
                eq(account.id()), eq(VerificationPurpose.PASSWORD_RESET), any(Instant.class));
        verify(verificationTokenRepository).save(any());

        ArgumentCaptor<PasswordResetEmailRequested> eventCaptor = ArgumentCaptor.forClass(PasswordResetEmailRequested.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipient()).isEqualTo(new Email("titular@example.com"));
    }

    @Test
    void nonExistentAccountDoesNothingAndNeverThrows() {
        when(accountRepository.findByEmail(any())).thenReturn(Optional.empty());

        useCase.requestReset(new RequestPasswordResetCommand("nadie@example.com"));

        verify(verificationTokenRepository, never()).invalidateActiveTokens(any(), any(), any());
        verify(verificationTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void lockedAccountStillGetsAResetToken() {
        // A diferencia del login, ninguna AC de esta historia restringe por
        // AccountStatus — una Cuenta LOCKED también puede recuperar su acceso.
        Account account = Account.reconstitute(
                com.auth_service.auth.domain.model.AccountId.newId(),
                new Email("bloqueado@example.com"), new HashedPassword("bcrypt-hash"),
                com.auth_service.auth.domain.model.AccountStatus.LOCKED,
                java.util.Set.of(com.auth_service.auth.domain.model.Role.USER), 5, Instant.now().plusSeconds(600), Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        useCase.requestReset(new RequestPasswordResetCommand("bloqueado@example.com"));

        verify(verificationTokenRepository).save(any());
        verify(eventPublisher).publishEvent(any(PasswordResetEmailRequested.class));
    }
}
