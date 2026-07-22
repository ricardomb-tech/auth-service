package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.command.LogoutCommand;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogoutUseCaseTest {

    private static final String RAW_TOKEN = "raw-refresh-token";
    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LogoutUseCase useCase = new LogoutUseCase(refreshTokenRepository, clock);

    private RefreshToken tokenWith(UUID familyId, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return RefreshToken.reconstitute(UUID.randomUUID(), AccountId.newId(), "irrelevant-hash", familyId, expiresAt, usedAt, revokedAt);
    }

    @Test
    void unrecognizedTokenHashDoesNotThrowAndNeverRevokesAnything() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        useCase.logout(new LogoutCommand(RAW_TOKEN));

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void validUnusedTokenRevokesItsFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        useCase.logout(new LogoutCommand(RAW_TOKEN));

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), eq(NOW));
    }

    @Test
    void alreadyUsedTokenStillRevokesItsFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(familyId, NOW.plusSeconds(3600), NOW.minusSeconds(10), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        useCase.logout(new LogoutCommand(RAW_TOKEN));

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), eq(NOW));
    }

    @Test
    void alreadyRevokedTokenStillCallsRevokeFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(familyId, NOW.plusSeconds(3600), null, NOW.minusSeconds(10));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        useCase.logout(new LogoutCommand(RAW_TOKEN));

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), eq(NOW));
    }

    @Test
    void expiredTokenStillCallsRevokeFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(familyId, NOW.minusSeconds(1), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        useCase.logout(new LogoutCommand(RAW_TOKEN));

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), eq(NOW));
    }
}
