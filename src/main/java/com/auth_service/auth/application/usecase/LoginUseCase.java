package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.LockoutProperties;
import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-3 — orquesta el login con credenciales. Email con formato inválido,
 * Cuenta inexistente, password incorrecta y Cuenta no ACTIVE terminan todas
 * en el mismo {@link AuthenticationFailedException} sin bifurcación de
 * mensaje ni de código HTTP (NFR-2, AD-8) — el orden de los chequeos y el
 * mensaje genérico único garantizan esto por construcción.
 *
 * <p>{@code noRollbackFor}: sin esto, Spring haría rollback del propio
 * {@code save()} de {@link #recordFailedAttempt} (incremento de
 * {@code failed_attempts}, transición a {@code LOCKED}) al escapar
 * {@link AuthenticationFailedException}, dejando el contador de fuerza
 * bruta sin persistir pese al 401 devuelto al cliente (Story 3.2, FR-8/FR-9)
 * — solo un test de integración transaccional real lo detecta, los mocks de
 * {@code AccountRepository} no simulan rollback real.
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
    private final LockoutProperties lockoutProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LoginUseCase(AccountRepository accountRepository, PasswordHasher passwordHasher, TokenIssuer tokenIssuer,
                         LockoutProperties lockoutProperties, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.lockoutProperties = lockoutProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
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
        Instant now = Instant.now(clock);

        // Auto-desbloqueo (Story 3.2, AC #4): si el bloqueo ya expiró, la
        // Cuenta vuelve a operar como ACTIVE antes de evaluar credenciales —
        // AD-6/AD-13, la transición vive aquí, no en domain/model. Debe
        // ocurrir ANTES de la comparación de contraseña: si no, una Cuenta
        // ya desbloqueable seguiría rechazándose en el chequeo de status de
        // más abajo, que solo se alcanza tras validar la contraseña.
        if (account.unlockIfExpired(now)) {
            accountRepository.save(account);
        }

        if (account.passwordHash() == null) {
            // Cuenta creada solo vía login federado (Story 2.1) — no tiene
            // contraseña local que comparar. Sin este guard,
            // passwordHasher.matches(raw, null) lanza NullPointerException,
            // que escapa como 500 y filtra "este email no tiene password"
            // (fuga de enumeración, NFR-2/AD-8). No cuenta como intento
            // fallido de fuerza bruta (Review Findings, Story 4.1): no hay
            // contraseña local que adivinar, y contarlo permitiría bloquear
            // indefinidamente una Cuenta solo-federada vía este endpoint que
            // su titular nunca usa.
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        boolean passwordMatches;
        try {
            passwordMatches = passwordHasher.matches(command.rawPassword(), account.passwordHash());
        } catch (IllegalArgumentException unreadableHash) {
            // Un hash persistido en un formato que el encoder ya no reconoce es un
            // problema de datos, no del solicitante — sigue siendo credencial inválida.
            recordFailedAttempt(account, now);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }
        if (!passwordMatches) {
            recordFailedAttempt(account, now);
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }
        if (account.status() != AccountStatus.ACTIVE) {
            // La contraseña ya fue correcta aquí — no es un fallo de
            // credencial (Story 3.2, AC #3): ni se distingue el motivo ni se
            // penaliza más un intento contra una Cuenta ya bloqueada.
            throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);
        }

        if (account.failedAttempts() > 0) {
            account.recordSuccessfulLogin();
            accountRepository.save(account);
        }

        return tokenIssuer.issue(account);
    }

    private void recordFailedAttempt(Account account, Instant now) {
        if (account.status() != AccountStatus.ACTIVE) {
            return;
        }
        boolean justLocked = account.recordFailedLoginAttempt(lockoutProperties.threshold(), lockoutProperties.lockDuration(), now);
        accountRepository.save(account);
        if (justLocked) {
            eventPublisher.publishEvent(new AccountLockedEmailRequested(account.email(), account.lockedUntil()));
        }
    }
}
