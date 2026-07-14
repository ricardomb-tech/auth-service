package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.InvalidRefreshTokenException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-4 — canjea un Refresh Token vigente por un par nuevo de la misma
 * Familia (AD-2, AD-4, AD-13), detectando reuso (rotación ya efectuada) y
 * revocando la Familia completa en ese caso (AC #2).
 *
 * <p>{@code noRollbackFor}: sin esto, Spring haría rollback del propio
 * {@code UPDATE} de {@code revokeFamily} al escapar {@link InvalidRefreshTokenException},
 * dejando la familia sin revocar en la base de datos pese al 401 devuelto al
 * cliente — solo el test de integración (Task 7) lo detecta, los mocks no
 * simulan rollback real.</p>
 */
@Service
public class RefreshTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenUseCase.class);
    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Refresh token inválido o expirado.";

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountRepository accountRepository;
    private final TokenIssuer tokenIssuer;
    private final Clock clock;

    public RefreshTokenUseCase(RefreshTokenRepository refreshTokenRepository, AccountRepository accountRepository,
                                TokenIssuer tokenIssuer, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.accountRepository = accountRepository;
        this.tokenIssuer = tokenIssuer;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenIssuer.IssuedTokens refresh(RefreshCommand command) {
        String tokenHash = RefreshToken.hashRawToken(command.rawRefreshToken());

        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE));

        Instant now = Instant.now(clock);

        // Reuso se chequea antes que expiración/revocación: un token ya usado
        // es señal de robo por sí solo, incluso si además ya expiró — revisión
        // de la Story 1.5, ver Review Findings.
        if (token.usedAt() != null) {
            revokeFamilyOnReuse(token, now);
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        if (now.isAfter(token.expiresAt()) || token.revokedAt() != null) {
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        // Estado de Cuenta se verifica antes de consumir el token: si la Cuenta
        // no está ACTIVE, el token queda intacto (sin used_at) en vez de
        // quemarse sin emitir par nuevo — revisión de la Story 1.5, ver Review
        // Findings.
        Optional<Account> account = accountRepository.findById(token.accountId());
        if (account.isEmpty() || account.get().status() != AccountStatus.ACTIVE) {
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        int affected = refreshTokenRepository.markUsedIfUnused(token.id(), now);
        if (affected == 0) {
            // Carrera perdida: otra petición concurrente (o la revocación de un
            // hermano de la familia) ganó entre la lectura y este UPDATE.
            revokeFamilyOnReuse(token, now);
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        return tokenIssuer.issue(account.get(), token.familyId());
    }

    private void revokeFamilyOnReuse(RefreshToken token, Instant now) {
        log.warn("Refresh token reuse detected, revoking family. accountId={} familyId={}",
                token.accountId(), token.familyId());
        refreshTokenRepository.revokeFamily(token.familyId(), now);
    }
}
