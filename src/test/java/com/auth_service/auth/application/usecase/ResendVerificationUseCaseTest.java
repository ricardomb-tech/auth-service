package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResendVerificationUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final VerificationTokenRepository verificationTokenRepository = mock(VerificationTokenRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private ResendVerificationUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResendVerificationUseCase(accountRepository, verificationTokenRepository,
                new AuthTokenProperties(Duration.ofHours(24), Duration.ofDays(7), Duration.ofHours(1)), eventPublisher, clock);
    }

    @Test
    void pendingAccountGetsNewTokenAndInvalidatesOldOnes() {
        Account account = Account.register(new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"));
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        ResendVerificationResult result = useCase.resend(new ResendVerificationCommand("visitante@example.com"));

        assertThat(result).isEqualTo(ResendVerificationResult.RESENT);
        verify(verificationTokenRepository).invalidateActiveTokens(
                eq(account.id()), eq(VerificationPurpose.EMAIL_VERIFICATION), any(Instant.class));
        verify(verificationTokenRepository).save(any());

        ArgumentCaptor<VerificationEmailRequested> eventCaptor = ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipient()).isEqualTo(new Email("visitante@example.com"));
    }

    @Test
    void findsAccountRegardlessOfEmailCase() {
        Account account = Account.register(new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"));
        when(accountRepository.findByEmail(new Email("visitante@example.com"))).thenReturn(Optional.of(account));

        // Email ya normaliza a minúsculas en su constructor (AD-14, patch de la Story 1.2) —
        // esto confirma que ResendVerificationUseCase no necesita normalizar aparte.
        ResendVerificationResult result = useCase.resend(new ResendVerificationCommand("Visitante@Example.COM"));

        assertThat(result).isEqualTo(ResendVerificationResult.RESENT);
    }

    @Test
    void nonExistentAccountReturnsNotApplicableWithoutTouchingTokens() {
        when(accountRepository.findByEmail(any())).thenReturn(Optional.empty());

        ResendVerificationResult result = useCase.resend(new ResendVerificationCommand("nadie@example.com"));

        assertThat(result).isEqualTo(ResendVerificationResult.NOT_APPLICABLE);
        verify(verificationTokenRepository, never()).invalidateActiveTokens(any(), any(), any());
        verify(verificationTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void alreadyActiveAccountReturnsNotApplicableWithoutTouchingTokens() {
        Account activeAccount = Account.reconstitute(
                com.auth_service.auth.domain.model.AccountId.newId(),
                new Email("activo@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(com.auth_service.auth.domain.model.Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(activeAccount));

        ResendVerificationResult result = useCase.resend(new ResendVerificationCommand("activo@example.com"));

        assertThat(result).isEqualTo(ResendVerificationResult.NOT_APPLICABLE);
        verify(verificationTokenRepository, never()).invalidateActiveTokens(any(), any(), any());
        verify(verificationTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
