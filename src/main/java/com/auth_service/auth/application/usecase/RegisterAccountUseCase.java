package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.result.RegisterAccountResult;

import com.auth_service.auth.application.usecase.command.RegisterAccountCommand;

import com.auth_service.auth.application.event.VerificationEmailRequested;
import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * FR-1 — orquesta el registro de una Cuenta. Punto único de entrada para el
 * futuro Domain Event {@code AccountRegistered} (Story 6.1, AD-15), que
 * extenderá este caso de uso sin cambiar su forma (AD-13).
 */
@Service
public class RegisterAccountUseCase {

    private final AccountRepository accountRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordHasher passwordHasher;
    private final AuthTokenProperties authTokenProperties;
    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    public RegisterAccountUseCase(AccountRepository accountRepository,
                                   VerificationTokenRepository verificationTokenRepository,
                                   PasswordHasher passwordHasher,
                                   AuthTokenProperties authTokenProperties,
                                   ApplicationEventPublisher eventPublisher,
                                   Clock clock) {
        this.accountRepository = accountRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordHasher = passwordHasher;
        this.authTokenProperties = authTokenProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public RegisterAccountResult register(RegisterAccountCommand command) {
        Email email = new Email(command.rawEmail());

        Optional<Account> existing = accountRepository.findByEmail(email);
        if (existing.isPresent()) {
            return RegisterAccountResult.EMAIL_ALREADY_REGISTERED;
        }

        RawPassword rawPassword = new RawPassword(command.rawPassword());
        HashedPassword hashedPassword = passwordHasher.hash(rawPassword);

        Account account = Account.register(email, hashedPassword);
        accountRepository.save(account);

        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION,
                authTokenProperties.verificationTtl(), clock);
        verificationTokenRepository.save(issued.token());

        eventPublisher.publishEvent(new VerificationEmailRequested(email, issued.rawToken()));

        return RegisterAccountResult.ACCOUNT_CREATED;
    }
}
