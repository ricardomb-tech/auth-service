package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.command.LogoutCommand;

import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-5 — invalida el Refresh Token presentado revocando su Familia completa
 * (AD-4, AD-13). Idempotente y sin distinguir el motivo de rechazo: un token
 * no reconocido, ya usado, ya revocado o expirado terminan igual en un 204
 * silencioso, nunca en una excepción.
 */
@Service
public class LogoutUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public LogoutUseCase(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Transactional
    public void logout(LogoutCommand command) {
        String tokenHash = RefreshToken.hashRawToken(command.rawRefreshToken());

        Optional<RefreshToken> token = refreshTokenRepository.findByTokenHash(tokenHash);
        if (token.isEmpty()) {
            return;
        }

        refreshTokenRepository.revokeFamily(token.get().familyId(), Instant.now(clock));
    }
}
