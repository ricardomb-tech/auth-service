package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.OAuth2ExchangeFailedException;
import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import org.springframework.stereotype.Service;

/**
 * Canjea el código de un solo uso emitido por {@code
 * OAuth2AuthenticationSuccessHandler} por el par Access+Refresh real —
 * mueve la entrega de tokens de la URL de redirección (visible en historial
 * del navegador y logs) a un {@code POST} explícito del cliente.
 */
@Service
public class OAuth2ExchangeUseCase {

    private final OAuth2ExchangeCodeStore exchangeCodeStore;

    public OAuth2ExchangeUseCase(OAuth2ExchangeCodeStore exchangeCodeStore) {
        this.exchangeCodeStore = exchangeCodeStore;
    }

    public TokenIssuer.IssuedTokens exchange(OAuth2ExchangeCommand command) {
        OAuth2ExchangeCodeStore.IssuedTokens tokens = exchangeCodeStore.redeem(command.code())
                .orElseThrow(() -> new OAuth2ExchangeFailedException("Código de intercambio inválido, ya usado o expirado."));
        return new TokenIssuer.IssuedTokens(tokens.accessToken(), tokens.refreshToken(), tokens.accessTokenExpiresInSeconds());
    }
}
