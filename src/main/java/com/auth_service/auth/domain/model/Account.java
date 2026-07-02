package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Aggregate root. Mutable a través de su ciclo de vida (historias futuras
 * añaden las transiciones de {@link AccountStatus} — AD-6). Esta historia
 * solo cubre el nacimiento de la Cuenta vía {@link #register}.
 */
public class Account {

    private final AccountId id;
    private final Email email;
    private final HashedPassword passwordHash;
    private AccountStatus status;
    private final Set<Role> roles;
    private int failedAttempts;
    private Instant lockedUntil;
    private final Instant createdAt;

    private Account(AccountId id, Email email, HashedPassword passwordHash, AccountStatus status,
                     Set<Role> roles, int failedAttempts, Instant lockedUntil, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.roles = roles;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.createdAt = createdAt;
    }

    /** Nace PENDING_VERIFICATION con Rol USER — FR-1. */
    public static Account register(Email email, HashedPassword passwordHash) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.USER);
        return new Account(AccountId.newId(), email, passwordHash, AccountStatus.PENDING_VERIFICATION,
                roles, 0, null, Instant.now());
    }

    /** Reconstruye una Cuenta ya persistida — usado por el adapter de persistencia. */
    public static Account reconstitute(AccountId id, Email email, HashedPassword passwordHash, AccountStatus status,
                                        Set<Role> roles, int failedAttempts, Instant lockedUntil, Instant createdAt) {
        return new Account(id, email, passwordHash, status, new HashSet<>(roles), failedAttempts, lockedUntil, createdAt);
    }

    /**
     * Transición PENDING_VERIFICATION → ACTIVE (Story 1.3, FR-2). Quién y
     * cuándo invocarla es responsabilidad exclusiva de application/usecase
     * (AD-6, AD-13) — este método solo aplica la transición si es válida.
     */
    public void activate() {
        if (status != AccountStatus.PENDING_VERIFICATION) {
            throw new DomainValidationException("Solo una Cuenta pendiente de verificación puede activarse.");
        }
        this.status = AccountStatus.ACTIVE;
    }

    public AccountId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public HashedPassword passwordHash() {
        return passwordHash;
    }

    public AccountStatus status() {
        return status;
    }

    public Set<Role> roles() {
        return Set.copyOf(roles);
    }

    public int failedAttempts() {
        return failedAttempts;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
