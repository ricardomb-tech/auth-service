package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.OAuth2ExchangeFailedException;
import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2ExchangeUseCaseTest {

    private final OAuth2ExchangeCodeStore exchangeCodeStore = mock(OAuth2ExchangeCodeStore.class);
    private final OAuth2ExchangeUseCase useCase = new OAuth2ExchangeUseCase(exchangeCodeStore);

    @Test
    void validCodeReturnsTheStoredTokens() {
        OAuth2ExchangeCodeStore.IssuedTokens stored =
                new OAuth2ExchangeCodeStore.IssuedTokens("access-abc", "refresh-xyz", 900L);
        when(exchangeCodeStore.redeem(eq("valid-code"))).thenReturn(Optional.of(stored));

        TokenIssuer.IssuedTokens result = useCase.exchange(new OAuth2ExchangeCommand("valid-code"));

        assertThat(result.accessToken()).isEqualTo("access-abc");
        assertThat(result.refreshToken()).isEqualTo("refresh-xyz");
        assertThat(result.accessTokenExpiresInSeconds()).isEqualTo(900L);
    }

    @Test
    void unknownUsedOrExpiredCodeThrowsOAuth2ExchangeFailedException() {
        when(exchangeCodeStore.redeem(eq("bad-code"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.exchange(new OAuth2ExchangeCommand("bad-code")))
                .isInstanceOf(OAuth2ExchangeFailedException.class);
    }
}
