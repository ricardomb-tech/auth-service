package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

/**
 * Value Object — AD-14. Wrapper sobre un hash ya calculado (ver
 * {@link com.auth_service.auth.domain.port.PasswordHasher}). No calcula el
 * hash — solo garantiza que nunca se construye vacío.
 */
public record HashedPassword(String value) {

    public HashedPassword {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("El hash de la contraseña no puede estar vacío.");
        }
    }

    @Override
    public String toString() {
        return "HashedPassword[REDACTED]";
    }
}
