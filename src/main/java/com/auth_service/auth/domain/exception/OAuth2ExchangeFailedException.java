package com.auth_service.auth.domain.exception;

/**
 * Código de canje de OAuth2 no reconocido, ya canjeado o expirado — mapea a
 * 401 genérico, sin distinguir el motivo (mismo principio que {@link
 * InvalidRefreshTokenException}, AD-8). No lo reutiliza: dominios de error
 * distintos (token de sesión vs. código de canje de un solo uso). Java puro (AD-1).
 */
public class OAuth2ExchangeFailedException extends RuntimeException {

    public OAuth2ExchangeFailedException(String message) {
        super(message);
    }
}
