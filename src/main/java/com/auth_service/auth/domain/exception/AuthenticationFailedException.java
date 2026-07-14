package com.auth_service.auth.domain.exception;

/**
 * Credenciales inválidas o Cuenta en un estado que no permite login —
 * mapea a 401, no a 400 como {@link DomainValidationException} (NFR-2:
 * mismo error genérico sin distinguir motivo). Java puro (AD-1).
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
