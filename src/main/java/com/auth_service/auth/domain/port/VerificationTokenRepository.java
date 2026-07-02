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
}
