package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
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

/** FR-2 — reemite el Token de Verificación de una Cuenta pendiente, invalidando el/los anteriores. */
@Service
public class ResendVerificationUseCase {

    private final AccountRepository accountRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final AuthTokenProperties authTokenProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ResendVerificationUseCase(AccountRepository accountRepository,
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
    public ResendVerificationResult resend(ResendVerificationCommand command) {
        Email email = new Email(command.rawEmail());

        Optional<Account> maybeAccount = accountRepository.findByEmail(email);
        if (maybeAccount.isEmpty()) {
            return ResendVerificationResult.NOT_APPLICABLE;
        }

        Account account = maybeAccount.get();
        if (account.status() != AccountStatus.PENDING_VERIFICATION) {
            return ResendVerificationResult.NOT_APPLICABLE;
        }

        verificationTokenRepository.invalidateActiveTokens(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION, Instant.now(clock));

        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION, authTokenProperties.verificationTtl(), clock);
        verificationTokenRepository.save(issued.token());

        eventPublisher.publishEvent(new VerificationEmailRequested(email, issued.rawToken()));

        return ResendVerificationResult.RESENT;
    }
}
