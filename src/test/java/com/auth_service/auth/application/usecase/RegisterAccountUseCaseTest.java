package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.result.RegisterAccountResult;

import com.auth_service.auth.application.usecase.command.RegisterAccountCommand;

import com.auth_service.auth.application.event.VerificationEmailRequested;
import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegisterAccountUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final VerificationTokenRepository verificationTokenRepository = mock(VerificationTokenRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private RegisterAccountUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterAccountUseCase(accountRepository, verificationTokenRepository, passwordHasher,
                new AuthTokenProperties(Duration.ofHours(24), Duration.ofDays(7), Duration.ofHours(1)), eventPublisher, Clock.systemUTC());
    }

    @Test
    void newEmailCreatesAccountIssuesTokenAndPublishesEvent() {
        when(accountRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordHasher.hash(any())).thenReturn(new HashedPassword("bcrypt-hash"));

        RegisterAccountResult result = useCase.register(new RegisterAccountCommand("visitante@example.com", "Str0ngPass"));

        assertThat(result).isEqualTo(RegisterAccountResult.ACCOUNT_CREATED);
        verify(accountRepository).save(any(Account.class));
        verify(verificationTokenRepository).save(any());

        ArgumentCaptor<VerificationEmailRequested> eventCaptor = ArgumentCaptor.forClass(VerificationEmailRequested.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().recipient()).isEqualTo(new Email("visitante@example.com"));
        assertThat(eventCaptor.getValue().rawToken()).isNotBlank();
    }

    @Test
    void alreadyRegisteredEmailDoesNotPersistOrPublish() {
        Account existing = Account.register(new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"));
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(existing));

        RegisterAccountResult result = useCase.register(new RegisterAccountCommand("visitante@example.com", "Str0ngPass"));

        assertThat(result).isEqualTo(RegisterAccountResult.EMAIL_ALREADY_REGISTERED);
        verify(accountRepository, never()).save(any());
        verify(verificationTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
