package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.RefreshToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** UPDATE condicional y atómico: solo marca `used_at` si `used_at IS NULL AND revoked_at IS NULL`. Devuelve filas afectadas (0 o 1). */
    int markUsedIfUnused(UUID tokenId, Instant usedAt);

    /** Revoca (marca `revoked_at`) todas las filas de la familia que aún no estén revocadas, en un único UPDATE masivo. Devuelve filas afectadas. */
    int revokeFamily(UUID familyId, Instant revokedAt);

    /** Revoca (marca `revoked_at`) todas las familias de la Cuenta que aún no estén revocadas, en un único UPDATE masivo. Devuelve filas afectadas. */
    int revokeAllForAccount(AccountId accountId, Instant revokedAt);
}
