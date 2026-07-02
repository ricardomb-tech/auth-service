package com.auth_service.auth.domain.exception;

/**
 * Invariante de dominio violado (formato inválido, política incumplida).
 * Java puro — sin dependencia de Spring/Jakarta (AD-1).
 */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
