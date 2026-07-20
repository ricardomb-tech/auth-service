package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;

import java.time.Instant;
import java.util.Optional;

public interface VerificationTokenRepository {

    VerificationToken save(VerificationToken token);

    Optional<VerificationToken> findByTokenHash(String tokenHash);

    /** Invalida (marca como consumido) todo token sin consumir de la Cuenta y propósito dados. Devuelve cuántas filas afectó. */
    int invalidateActiveTokens(AccountId accountId, VerificationPurpose purpose, Instant now);

    /**
     * Consume atómicamente el token con este hash si sigue activo (no consumido, no expirado).
     * Guarda anti-carrera: dos llamadas concurrentes con el mismo hash solo pueden ver una
     * fila afectada entre ambas. Devuelve 1 si tuvo efecto, 0 si el token ya no estaba activo.
     */
    int consumeIfActive(String tokenHash, Instant now);
}
