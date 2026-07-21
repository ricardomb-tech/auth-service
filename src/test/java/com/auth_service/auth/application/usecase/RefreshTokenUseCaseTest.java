package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.InvalidRefreshTokenException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenUseCaseTest {

    private static final String RAW_TOKEN = "raw-refresh-token";
    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RefreshTokenUseCase useCase =
            new RefreshTokenUseCase(refreshTokenRepository, accountRepository, tokenIssuer, clock);

    private RefreshToken tokenWith(AccountId accountId, UUID familyId, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return RefreshToken.reconstitute(UUID.randomUUID(), accountId, "irrelevant-hash", familyId, expiresAt, usedAt, revokedAt);
    }

    private Account activeAccount(AccountId id) {
        return Account.reconstitute(id, new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
    }

    @Test
    void unrecognizedTokenHashThrowsWithoutTouchingTheRepositoryFurther() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void expiredTokenThrowsWithoutTouchingTheRepositoryFurther() {
        AccountId accountId = AccountId.newId();
        RefreshToken token = tokenWith(accountId, UUID.randomUUID(), NOW.minusSeconds(1), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void revokedTokenThrowsWithoutTouchingTheRepositoryFurther() {
        AccountId accountId = AccountId.newId();
        RefreshToken token = tokenWith(accountId, UUID.randomUUID(), NOW.plusSeconds(3600), null, NOW.minusSeconds(10));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void alreadyUsedTokenRevokesTheFamilyAndThrowsWithoutIssuingNewTokens() {
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), NOW.minusSeconds(10), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), any());
        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void reusedTokenThatHasAlsoExpiredStillRevokesTheFamily() {
        // Reuso se chequea antes que expiración (Review Findings, Story 1.5):
        // un token ya usado es señal de robo incluso si además ya expiró.
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.minusSeconds(1), NOW.minusSeconds(10), null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void validUnusedTokenMarksItUsedAndIssuesANewPairForTheSameFamily() {
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        when(refreshTokenRepository.markUsedIfUnused(eq(token.id()), any())).thenReturn(1);
        Account account = activeAccount(accountId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access", "refresh", 900L);
        when(tokenIssuer.issue(account, familyId)).thenReturn(issuedTokens);

        TokenIssuer.IssuedTokens result = useCase.refresh(new RefreshCommand(RAW_TOKEN));

        assertThat(result).isEqualTo(issuedTokens);
        verify(refreshTokenRepository).markUsedIfUnused(eq(token.id()), any());
        verify(tokenIssuer).issue(account, familyId);
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void lostRaceOnMarkUsedIfUnusedRevokesTheFamilyAndThrowsEvenThoughTheInitialReadLookedValid() {
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(accountId)));
        when(refreshTokenRepository.markUsedIfUnused(eq(token.id()), any())).thenReturn(0);

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, times(1)).revokeFamily(eq(familyId), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "LOCKED", "DISABLED"})
    void nonActiveAccountThrowsWithoutConsumingTheTokenOrIssuingNewTokens(AccountStatus status) {
        // Estado de Cuenta se verifica antes de consumir el token (Review
        // Findings, Story 1.5): un token válido sin usar debe quedar intacto
        // si la Cuenta no está ACTIVE, no marcarse used_at sin emitir par nuevo.
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        Account account = Account.reconstitute(accountId, new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                status, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void expiredLockAutoUnlocksBeforeStatusCheckAndAllowsRefresh() {
        // Review Findings (Story 4.1): sin esto, un refresh token vigente
        // emitido antes de un bloqueo por fuerza bruta quedaría rechazado
        // para siempre tras expirar el bloqueo si el titular no vuelve a
        // pasar por /auth/login.
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        Account lockedAccount = Account.reconstitute(accountId, new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, NOW.minusSeconds(1), Instant.now());
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(lockedAccount));
        when(refreshTokenRepository.markUsedIfUnused(eq(token.id()), any())).thenReturn(1);
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access", "refresh", 900L);
        when(tokenIssuer.issue(lockedAccount, familyId)).thenReturn(issuedTokens);

        TokenIssuer.IssuedTokens result = useCase.refresh(new RefreshCommand(RAW_TOKEN));

        assertThat(result).isEqualTo(issuedTokens);
        assertThat(lockedAccount.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(lockedAccount);
    }

    @Test
    void stillLockedAccountThrowsWithoutUnlockingOrConsumingTheToken() {
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        Account lockedAccount = Account.reconstitute(accountId, new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, NOW.plusSeconds(600), Instant.now());
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(lockedAccount));

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(lockedAccount.status()).isEqualTo(AccountStatus.LOCKED);
        verify(accountRepository, never()).save(any());
        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }

    @Test
    void missingAccountThrowsWithoutConsumingTheTokenOrIssuingNewTokens() {
        AccountId accountId = AccountId.newId();
        UUID familyId = UUID.randomUUID();
        RefreshToken token = tokenWith(accountId, familyId, NOW.plusSeconds(3600), null, null);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.refresh(new RefreshCommand(RAW_TOKEN)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).markUsedIfUnused(any(), any());
        verify(tokenIssuer, never()).issue(any(), any());
    }
}
