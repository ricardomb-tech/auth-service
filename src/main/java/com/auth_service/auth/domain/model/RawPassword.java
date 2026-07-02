package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

/**
 * Value Object — AD-14. Contraseña en claro, solo de tránsito: nunca se
 * persiste ni se loggea (AD-5). Valida la política de contraseña en
 * construcción (≥8 caracteres, mayúscula, minúscula, dígito) — PRD FR-1.
 */
public record RawPassword(String value) {

    /** BCrypt trunca silenciosamente más allá de 72 bytes — rechazamos antes de que eso pase inadvertido. */
    private static final int MAX_LENGTH = 72;

    public RawPassword {
        if (value == null || value.length() < 8) {
            throw new DomainValidationException("La contraseña debe tener al menos 8 caracteres.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new DomainValidationException("La contraseña no puede superar los " + MAX_LENGTH + " caracteres.");
        }
        if (value.chars().noneMatch(Character::isUpperCase)) {
            throw new DomainValidationException("La contraseña debe incluir al menos una mayúscula.");
        }
        if (value.chars().noneMatch(Character::isLowerCase)) {
            throw new DomainValidationException("La contraseña debe incluir al menos una minúscula.");
        }
        if (value.chars().noneMatch(Character::isDigit)) {
            throw new DomainValidationException("La contraseña debe incluir al menos un dígito.");
        }
    }

    @Override
    public String toString() {
        return "RawPassword[REDACTED]";
    }
}
