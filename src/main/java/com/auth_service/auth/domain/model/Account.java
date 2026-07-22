package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

import java.time.Duration;
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
    private HashedPassword passwordHash;
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

    /**
     * Nace ACTIVE directamente, sin contraseña local — FR-6 (Story 2.1). El
     * proveedor federado (Google/GitHub) ya verificó el email, no hay nada
     * que confirmar por correo a diferencia de {@link #register}.
     */
    public static Account registerFederated(Email email) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.USER);
        return new Account(AccountId.newId(), email, null, AccountStatus.ACTIVE, roles, 0, null, Instant.now());
    }

    /**
     * Nace ACTIVE con Roles ADMIN+USER, sin verificación de email — FR-12
     * (Story 4.1, aprovisionamiento del primer Administrador). A diferencia
     * de {@link #register} (PENDING_VERIFICATION, solo USER) y
     * {@link #registerFederated} (ACTIVE, solo USER), esta Cuenta nace ya
     * operativa con ambos roles porque el operador que define
     * AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD ya controla el arranque del
     * proceso — no hay titular externo que verificar. Se invoca únicamente
     * desde el aprovisionamiento en el arranque (config/), nunca desde un
     * endpoint público.
     */
    public static Account registerAdmin(Email email, HashedPassword passwordHash) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.ADMIN);
        roles.add(Role.USER);
        return new Account(AccountId.newId(), email, passwordHash, AccountStatus.ACTIVE, roles, 0, null, Instant.now());
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

    /**
     * Restablecimiento de contraseña (Story 3.1, FR-7) — sin restricción de
     * {@link AccountStatus}: una Cuenta {@code LOCKED} también puede
     * recuperar su acceso por este camino; no la desbloquea (eso solo ocurre
     * en {@code LoginUseCase} al expirar {@code lockedUntil}).
     */
    public void changePassword(HashedPassword newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /**
     * Intento de login fallido contra esta Cuenta (Story 3.2, FR-8). Solo
     * incrementa y evalúa el umbral si la Cuenta está ACTIVE — AD-6 solo
     * permite la transición ACTIVE ⇄ LOCKED, nunca desde otro estado.
     * Devuelve true si este intento produjo el bloqueo (para que el caso de
     * uso decida si notificar por email — una sola vez, no en cada intento
     * posterior mientras ya está LOCKED).
     */
    public boolean recordFailedLoginAttempt(int lockoutThreshold, Duration lockoutDuration, Instant now) {
        if (status != AccountStatus.ACTIVE) {
            return false;
        }
        failedAttempts++;
        if (failedAttempts >= lockoutThreshold) {
            status = AccountStatus.LOCKED;
            lockedUntil = now.plus(lockoutDuration);
            return true;
        }
        return false;
    }

    /** Login exitoso (Story 3.2, FR-8) — reinicia el contador. */
    public void recordSuccessfulLogin() {
        this.failedAttempts = 0;
    }

    /**
     * Auto-desbloqueo (Story 3.2, FR-9) — transición LOCKED → ACTIVE solo si
     * el bloqueo ya expiró (AD-6). Devuelve true si desbloqueó, para que el
     * caso de uso decida si persistir el cambio.
     */
    public boolean unlockIfExpired(Instant now) {
        if (status == AccountStatus.LOCKED && lockedUntil != null && !now.isBefore(lockedUntil)) {
            status = AccountStatus.ACTIVE;
            lockedUntil = null;
            failedAttempts = 0;
            return true;
        }
        return false;
    }

    /**
     * Desactivación administrativa (Story 4.2, FR-11) — transición
     * {@code * → DISABLED} (AD-6: cualquier estado salvo ya DISABLED).
     * Quién puede invocarla y sobre quién es responsabilidad exclusiva de
     * {@code ManageAccountUseCase} (auto-protección, AC #4) — este método
     * solo aplica la transición si es válida.
     */
    public void disable() {
        if (status == AccountStatus.DISABLED) {
            throw new DomainValidationException("La Cuenta ya está desactivada.");
        }
        this.status = AccountStatus.DISABLED;
    }

    /** Reactivación administrativa (Story 4.2, FR-11) — transición DISABLED → ACTIVE únicamente. */
    public void reactivate() {
        if (status != AccountStatus.DISABLED) {
            throw new DomainValidationException("Solo una Cuenta desactivada puede reactivarse.");
        }
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Reemplaza el conjunto de Roles (Story 4.2, FR-11). Debe quedar al
     * menos un Rol — una Cuenta sin ningún Rol es un estado inválido que
     * ningún flujo del sistema sabe interpretar. La regla de "un Admin no
     * puede quitarse ADMIN a sí mismo" (AC #4) NO vive aquí — depende de
     * quién es el actor, algo que este objeto no conoce (AD-6); la decide
     * {@code ManageAccountUseCase} antes de invocar este método.
     */
    public void updateRoles(Set<Role> newRoles) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw new DomainValidationException("Una Cuenta debe tener al menos un Rol.");
        }
        this.roles.clear();
        this.roles.addAll(newRoles);
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
