package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.exception.FederatedLoginFailedException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.FederatedIdentity;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.FederatedIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * FR-6 (Story 2.1) — resuelve el login federado (Google/GitHub) tras la
 * autenticación ya completada por Spring Security OAuth2 Client: crea una
 * Cuenta nueva, vincula una Identidad Federada a una Cuenta existente, o
 * resuelve una Identidad ya vinculada, y reutiliza {@link TokenIssuer} (AD-2)
 * para emitir el mismo par de tokens que el login con credenciales.
 */
@Service
public class FederatedLoginUseCase {

    private final AccountRepository accountRepository;
    private final FederatedIdentityRepository federatedIdentityRepository;
    private final TokenIssuer tokenIssuer;
    private final Clock clock;

    public FederatedLoginUseCase(AccountRepository accountRepository, FederatedIdentityRepository federatedIdentityRepository,
                                  TokenIssuer tokenIssuer, Clock clock) {
        this.accountRepository = accountRepository;
        this.federatedIdentityRepository = federatedIdentityRepository;
        this.tokenIssuer = tokenIssuer;
        this.clock = clock;
    }

    @Transactional
    public TokenIssuer.IssuedTokens login(FederatedLoginCommand command) {
        FederatedProvider provider;
        try {
            provider = FederatedProvider.valueOf(command.provider().toUpperCase());
        } catch (IllegalArgumentException unrecognizedProvider) {
            throw new FederatedLoginFailedException("Proveedor de login federado no reconocido.");
        }

        // Vincular una Cuenta a un email que el proveedor no verificó abriría
        // un vector de account takeover — se rechaza antes de tocar cualquier
        // repositorio (AC #3).
        if (!command.emailVerified()) {
            throw new FederatedLoginFailedException("Email no verificado por el proveedor.");
        }

        Email email;
        try {
            email = new Email(command.rawEmail());
        } catch (DomainValidationException malformedEmail) {
            throw new FederatedLoginFailedException("Email del proveedor con formato inválido.");
        }

        Optional<FederatedIdentity> existingIdentity =
                federatedIdentityRepository.findByProviderAndProviderUserId(provider, command.providerUserId());

        Account account;
        if (existingIdentity.isPresent()) {
            account = accountRepository.findById(existingIdentity.get().accountId())
                    .orElseThrow(() -> new FederatedLoginFailedException("Cuenta vinculada a la Identidad Federada ya no existe."));
        } else {
            Optional<Account> existingAccount = accountRepository.findByEmail(email);
            if (existingAccount.isPresent()) {
                account = existingAccount.get();
            } else {
                account = Account.registerFederated(email);
                accountRepository.save(account);
            }
            FederatedIdentity identity = FederatedIdentity.link(account.id(), provider, command.providerUserId(), clock);
            federatedIdentityRepository.save(identity);
        }

        return tokenIssuer.issue(account);
    }
}
