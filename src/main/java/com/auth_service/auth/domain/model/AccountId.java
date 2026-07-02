package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

import java.util.UUID;

/**
 * Value Object — AD-14. El ID se genera en la aplicación, no en la base de
 * datos (Consistency Conventions del architecture spine).
 */
public record AccountId(UUID value) {

    public AccountId {
        if (value == null) {
            throw new DomainValidationException("El AccountId no puede ser nulo.");
        }
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }
}
