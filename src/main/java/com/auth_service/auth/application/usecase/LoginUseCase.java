package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * FR-3 — orquesta el login con credenciales. Email con formato inválido,
 * Cuenta inexistente, password incorrecta y Cuenta no ACTIVE terminan todas
 * en el mismo {@link AuthenticationFailedException} sin bifurcación de
 * mensaje ni de código HTTP (NFR-2, AD-8) — el orden de los chequeos y el
 * mensaje genérico único garantizan esto por construcción.
 */
@Service
public class LoginUseCase {

    // Mismo texto exacto que devuelve GlobalExceptionHandler al cliente — no se
    // usa ex.getMessage() ahí (mensaje fijo deliberado, ver su Javadoc), pero
    // mantener ambos strings idénticos evita que diverjan con el tiempo.
    private static final String INVALID_CREDENTIALS_MESSAGE = "Email o contraseña incorrectos.";

    private final AccountRepository accountRepository;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;

    public LoginUseCase(AccountRepository accountRepository, PasswordHasher passwordHasher, TokenIssuer tokenIssuer) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
    }

    @Transactional
    public TokenIssuer.IssuedTokens login(LoginCommand command) {
        Email email;
        try {
            email = new Email(command.rawEmail());
        } catch (DomainValidationException invalidFormat) {
            // Un email mal formado no debe distinguirse de una cuenta inexistente
            // (400 revelaría formato != 401 genérico, rompiendo AD-8).
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        Optional<Account> found = accountRepository.findByEmail(email);
        if (found.isEmpty()) {
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        Account account = found.get();
        if (account.passwordHash() == null) {
            // Cuenta creada solo vía login federado (Story 2.1) — no tiene
            // contraseña local que comparar. Sin este guard,
            // passwordHasher.matches(raw, null) lanza NullPointerException,
            // que escapa como 500 y filtra "este email no tiene password"
            // (fuga de enumeración, NFR-2/AD-8).
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        boolean passwordMatches;
        try {
            passwordMatches = passwordHasher.matches(command.rawPassword(), account.passwordHash());
        } catch (IllegalArgumentException unreadableHash) {
            // Un hash persistido en un formato que el encoder ya no reconoce es un
            // problema de datos, no del solicitante — sigue siendo credencial inválida.
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }
        if (!passwordMatches) {
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        return tokenIssuer.issue(account);
    }
}
