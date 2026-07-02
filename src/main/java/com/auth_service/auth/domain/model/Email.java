package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Value Object — AD-14. Valida el formato en construcción; nunca representa
 * un email inválido. Normaliza a minúsculas/sin espacios — un email es
 * case-insensitive por convención y esta es la única puerta de entrada al
 * concepto en todo el sistema, así que la unicidad en BD depende de esto.
 */
public record Email(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        if (value != null) {
            value = value.trim().toLowerCase(Locale.ROOT);
        }
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("El email no tiene un formato válido.");
        }
    }
}
