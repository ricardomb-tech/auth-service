package com.auth_service.auth.domain.port;

import java.util.Optional;

/**
 * Almacén de corta vida y un solo uso para el canje del código de OAuth2
 * (Story 2.1, revisión de seguridad) — desacopla la emisión de tokens
 * (durante el redirect de éxito) de su entrega al cliente (vía
 * {@code POST /auth/oauth2/exchange}), para no exponer Access+Refresh Token
 * como query params en la URL de redirección (historial del navegador, logs
 * de proxy/servidor, cabecera {@code Referer}).
 *
 * <p>No usa tipos de {@code application/usecase} (p. ej. {@code
 * TokenIssuer.IssuedTokens}) para no invertir la dirección de dependencia
 * (AD-1: domain no depende de application) — de ahí el record propio
 * {@link IssuedTokens}.</p>
 */
public interface OAuth2ExchangeCodeStore {

    record IssuedTokens(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {
    }

    /** Genera un código opaco de un solo uso, guarda los tokens asociados con TTL corto, y lo devuelve. */
    String issue(IssuedTokens tokens);

    /** Canjea el código: si existe y no expiró, lo consume (un solo uso) y devuelve los tokens; si no, vacío. */
    Optional<IssuedTokens> redeem(String code);
}
