package com.auth_service.auth.domain.exception;

/**
 * Refresh Token no reconocido, expirado, revocado o reusado — mapea a 401
 * genérico, sin distinguir el motivo (AC #2/#3 de la Story 1.5, AD-8). No
 * reutiliza {@link AuthenticationFailedException}: son dominios de error
 * distintos (credenciales de login vs. token de sesión). Java puro (AD-1).
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
