package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.command.RequestPasswordResetCommand;

import com.auth_service.auth.application.event.PasswordResetEmailRequested;
import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-7 — emite un Token {@code PASSWORD_RESET} para la Cuenta asociada al
 * email dado. Anti-enumeración (NFR-2): si la Cuenta no existe, no hace nada
 * y no lanza — el controller responde el mismo mensaje genérico en ambos
 * casos (AC #1).
 */
@Service
public class RequestPasswordResetUseCase {

    private final AccountRepository accountRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final AuthTokenProperties authTokenProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public RequestPasswordResetUseCase(AccountRepository accountRepository,
                                        VerificationTokenRepository verificationTokenRepository,
                                        AuthTokenProperties authTokenProperties,
                                        ApplicationEventPublisher eventPublisher,
                                        Clock clock) {
        this.accountRepository = accountRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.authTokenProperties = authTokenProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void requestReset(RequestPasswordResetCommand command) {
        Email email = new Email(command.email());

        Optional<Account> maybeAccount = accountRepository.findByEmail(email);
        if (maybeAccount.isEmpty()) {
            return;
        }

        Account account = maybeAccount.get();

        verificationTokenRepository.invalidateActiveTokens(
                account.id(), VerificationPurpose.PASSWORD_RESET, Instant.now(clock));

        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.PASSWORD_RESET, authTokenProperties.passwordResetTtl(), clock);
        verificationTokenRepository.save(issued.token());

        eventPublisher.publishEvent(new PasswordResetEmailRequested(email, issued.rawToken()));
    }
}
