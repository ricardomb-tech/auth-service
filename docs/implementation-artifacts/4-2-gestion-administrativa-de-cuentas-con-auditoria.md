---
baseline_commit: e4d842d6da04144d8a977ca45fec90ef32e5a0f9
---

# Story 4.2: Gestión administrativa de cuentas con auditoría

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Administrador,
I want listar, consultar, desactivar/reactivar Cuentas y modificar Roles, con rastro verificable de cada acción,
so that opere el sistema sin acceso directo a la base de datos y pueda responder "quién hizo qué". (FR-11, FR-13)

## Acceptance Criteria

1. Dado un Access Token con Rol `ADMIN`, cuando hago GET `/api/v1/admin/accounts`, entonces recibo el listado paginado de Cuentas (AD-18); y GET `/api/v1/admin/accounts/{id}` devuelve el detalle.
2. Dado un Access Token con Rol `USER` (sin `ADMIN`), cuando llamo a cualquier endpoint `/api/v1/admin/**`, entonces recibo 403 `application/problem+json` (AD-11, NFR-4).
3. Dado una Cuenta objetivo `ACTIVE` (o `LOCKED`/`PENDING_VERIFICATION`), cuando el Administrador la desactiva vía `POST /api/v1/admin/accounts/{id}/disable`, entonces pasa a `DISABLED`, todas sus Familias de Refresh Tokens quedan revocadas (AD-4) y no puede autenticarse por ninguna vía (credenciales ni OAuth2 — ya garantizado por el chequeo `status != ACTIVE` existente en `LoginUseCase`/`FederatedLoginUseCase`/`RefreshTokenUseCase`); en la misma transacción se inserta una fila en `audit_log` con actor, acción, Cuenta afectada, resultado y timestamp (AD-20, FR-13); y la reactivación vía `POST /api/v1/admin/accounts/{id}/reactivate` la devuelve a `ACTIVE` y también queda auditada.
4. Dado el propio Administrador autenticado, cuando intenta desactivarse a sí mismo (`POST .../disable` sobre su propio id) o quitarse el Rol `ADMIN` a sí mismo (`PUT .../roles` sin `ADMIN` en el nuevo conjunto, sobre su propio id), entonces la operación es rechazada con un error explícito (409) y el intento rechazado también se audita (fila `audit_log` con `result=REJECTED_SELF`).
5. Ninguna vía de la API permite editar ni eliminar una fila existente de `audit_log`; el propio esquema de PostgreSQL lo impide a nivel de trigger, no solo de código (ver Dev Notes — AD-20, addendum "se protege a nivel de rol de PostgreSQL, no solo de código").

## Tasks / Subtasks

- [x] Task 1: `Account` — transiciones `disable`/`reactivate`/`updateRoles` (AC: #3, #4)
  - [x] Añadir a `src/main/java/com/auth_service/auth/domain/model/Account.java`, junto a `activate()` (mismo estilo: valida la transición, lanza `DomainValidationException` si no es válida — AD-6 aplica también aquí aunque el disparo venga de `application/usecase`, no de `domain/model`):
    ```java
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
    ```
  - [x] Tests nuevos en `AccountTest` (mismo estilo que los de `recordFailedLoginAttempt`/`unlockIfExpired`):
    - `disableTransitionsActiveAccountToDisabled` — cuenta `ACTIVE` → `disable()` → `status() == DISABLED`.
    - `disableOnAlreadyDisabledAccountThrowsDomainValidationException`.
    - `reactivateTransitionsDisabledAccountToActive` — cuenta `DISABLED` → `reactivate()` → `status() == ACTIVE`.
    - `reactivateOnNonDisabledAccountThrowsDomainValidationException` (parametrizar sobre `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, mismo patrón `@ParameterizedTest @EnumSource` ya usado en la clase).
    - `updateRolesReplacesRoleSet` — cuenta con `{USER}` → `updateRoles(Set.of(ADMIN, USER))` → `roles()` contiene exactamente `{ADMIN, USER}`.
    - `updateRolesWithEmptySetThrowsDomainValidationException`.
    - `updateRolesWithNullThrowsDomainValidationException`.

- [x] Task 2: `AuditLogEntry` + enums `AdminAction`/`AuditResult` + port `AuditLogRepository` (AC: #3, #4, #5)
  - [x] Nuevo `src/main/java/com/auth_service/auth/domain/model/AdminAction.java`:
    ```java
    package com.auth_service.auth.domain.model;

    public enum AdminAction {
        DISABLE_ACCOUNT,
        REACTIVATE_ACCOUNT,
        UPDATE_ROLES
    }
    ```
  - [x] Nuevo `src/main/java/com/auth_service/auth/domain/model/AuditResult.java`:
    ```java
    package com.auth_service.auth.domain.model;

    public enum AuditResult {
        SUCCESS,
        REJECTED_SELF
    }
    ```
  - [x] Nuevo `src/main/java/com/auth_service/auth/domain/model/AuditLogEntry.java` (Java puro, AD-1/AD-14 — misma forma que un Value Object aunque no valide invariantes de negocio más allá de no-nulos, ya que representa un hecho ya ocurrido, no un concepto a validar):
    ```java
    package com.auth_service.auth.domain.model;

    import java.time.Instant;
    import java.util.UUID;

    /** Fila de auditoría inmutable (FR-13, AD-20) — un hecho ya ocurrido, nunca se edita tras crearse. */
    public record AuditLogEntry(UUID id, AccountId actorAccountId, AccountId targetAccountId, AdminAction action,
                                 AuditResult result, Instant occurredAt) {

        public static AuditLogEntry create(AccountId actorAccountId, AccountId targetAccountId, AdminAction action,
                                            AuditResult result, Instant occurredAt) {
            return new AuditLogEntry(UUID.randomUUID(), actorAccountId, targetAccountId, action, result, occurredAt);
        }
    }
    ```
  - [x] Nuevo port `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java`:
    ```java
    package com.auth_service.auth.domain.port;

    import com.auth_service.auth.domain.model.AuditLogEntry;

    public interface AuditLogRepository {

        void save(AuditLogEntry entry);
    }
    ```
    Solo `save` — la Story 4.3 (consulta del Registro de Auditoría) es la que necesita métodos de consulta por Cuenta/rango de fechas; añadirlos ahora sería scope creep sin AC que lo pida (mismo criterio que `existsByRole` en la Story 4.1).

- [x] Task 3: Persistencia de `audit_log` — entidad, repo JPA, adapter, migración append-only (AC: #3, #4, #5)
  - [x] Nueva migración `src/main/resources/db/migration/V5__audit_log.sql`:
    ```sql
    CREATE TABLE audit_log (
        id uuid PRIMARY KEY,
        actor_account_id uuid NOT NULL REFERENCES accounts (id),
        target_account_id uuid NOT NULL REFERENCES accounts (id),
        action text NOT NULL,
        result text NOT NULL,
        occurred_at timestamptz NOT NULL DEFAULT now()
    );

    -- AD-20 / addendum ("se protege a nivel de rol de PostgreSQL, no solo de
    -- código"): un REVOKE UPDATE/DELETE clásico NO basta aquí porque el rol
    -- de aplicación (DB_USER, ver docker-compose/.env.example) es también el
    -- ÚNICO rol de PostgreSQL del proyecto — el mismo que ejecuta esta
    -- migración y por tanto el DUEÑO de la tabla. En PostgreSQL el dueño de
    -- una tabla conserva sus privilegios DML implícitos pese a cualquier
    -- REVOKE explícito sobre su propio rol (no hay forma de que un rol se
    -- quite privilegios a sí mismo sobre algo que posee). Un trigger que
    -- rechaza incondicionalmente UPDATE/DELETE sí es una garantía real
    -- dentro de esta topología de un solo rol — es la alternativa elegida
    -- para esta historia. Separar un segundo rol de aplicación de bajo
    -- privilegio (dueño ≠ rol de runtime) sería la solución "de manual" con
    -- más de un rol de PostgreSQL, pero requiere tocar docker-compose/README
    -- (aprovisionamiento de roles) — fuera de alcance de esta historia,
    -- ver deferred-work.md.
    CREATE OR REPLACE FUNCTION reject_audit_log_mutation() RETURNS trigger AS $$
    BEGIN
        RAISE EXCEPTION 'audit_log es append-only: % no está permitido', TG_OP;
    END;
    $$ LANGUAGE plpgsql;

    CREATE TRIGGER audit_log_append_only
        BEFORE UPDATE OR DELETE ON audit_log
        FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();
    ```
  - [x] Nueva entidad `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogEntity.java` — mismo estilo que `AccountEntity` (constructor completo, getters, constructor protegido sin argumentos para JPA):
    ```java
    package com.auth_service.auth.infrastructure.adapters.postgresql;

    import jakarta.persistence.Column;
    import jakarta.persistence.Entity;
    import jakarta.persistence.Id;
    import jakarta.persistence.Table;

    import java.time.Instant;
    import java.util.UUID;

    /** Entidad JPA — nunca cruza hacia domain/ (AD-1); {@link AuditLogRepositoryAdapter} la mapea. */
    @Entity
    @Table(name = "audit_log")
    public class AuditLogEntity {

        @Id
        private UUID id;

        @Column(name = "actor_account_id", nullable = false)
        private UUID actorAccountId;

        @Column(name = "target_account_id", nullable = false)
        private UUID targetAccountId;

        @Column(nullable = false)
        private String action;

        @Column(nullable = false)
        private String result;

        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;

        protected AuditLogEntity() {
            // JPA
        }

        public AuditLogEntity(UUID id, UUID actorAccountId, UUID targetAccountId, String action, String result,
                               Instant occurredAt) {
            this.id = id;
            this.actorAccountId = actorAccountId;
            this.targetAccountId = targetAccountId;
            this.action = action;
            this.result = result;
            this.occurredAt = occurredAt;
        }

        public UUID getId() {
            return id;
        }

        public UUID getActorAccountId() {
            return actorAccountId;
        }

        public UUID getTargetAccountId() {
            return targetAccountId;
        }

        public String getAction() {
            return action;
        }

        public String getResult() {
            return result;
        }

        public Instant getOccurredAt() {
            return occurredAt;
        }
    }
    ```
  - [x] Nuevo `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java`:
    ```java
    package com.auth_service.auth.infrastructure.adapters.postgresql;

    import org.springframework.data.jpa.repository.JpaRepository;

    import java.util.UUID;

    public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    }
    ```
  - [x] Nuevo `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java`:
    ```java
    package com.auth_service.auth.infrastructure.adapters.postgresql;

    import com.auth_service.auth.domain.model.AuditLogEntry;
    import com.auth_service.auth.domain.port.AuditLogRepository;
    import org.springframework.stereotype.Component;

    @Component
    public class AuditLogRepositoryAdapter implements AuditLogRepository {

        private final AuditLogJpaRepository jpaRepository;

        public AuditLogRepositoryAdapter(AuditLogJpaRepository jpaRepository) {
            this.jpaRepository = jpaRepository;
        }

        @Override
        public void save(AuditLogEntry entry) {
            jpaRepository.save(new AuditLogEntity(entry.id(), entry.actorAccountId().value(), entry.targetAccountId().value(),
                    entry.action().name(), entry.result().name(), entry.occurredAt()));
        }
    }
    ```
  - [x] No hace falta test unitario aislado para el adapter (mismo criterio que `AccountRepositoryAdapter`, sin tests propios en este proyecto) — se cubre por la integración de Task 8.

- [x] Task 4: `AccountRepository` — listado paginado (AC: #1)
  - [x] Añadir al port `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java`:
    ```java
    java.util.List<Account> findAllPaged(int page, int size);

    long countAll();
    ```
    (import `java.util.List`). Sin `Pageable`/`Page` de Spring Data en la firma — `domain/` es Java puro (AD-1, `ArchitectureRulesTest.domain_should_be_framework_free` rompe el build si `domain/` importa `org.springframework..`). `page`/`size` son `int` planos; el adapter en `infrastructure/` es quien construye el `Pageable` de Spring Data.
  - [x] Implementar en `AccountRepositoryAdapter.java` (no hace falta ningún método nuevo en `AccountJpaRepository` — `JpaRepository` ya extiende `PagingAndSortingRepository`, que expone `findAll(Pageable)` y `count()`):
    ```java
    @Override
    public java.util.List<Account> findAllPaged(int page, int size) {
        return jpaRepository.findAll(org.springframework.data.domain.PageRequest.of(page, size))
                .map(this::toDomain)
                .getContent();
    }

    @Override
    public long countAll() {
        return jpaRepository.count();
    }
    ```
  - [x] No hace falta test unitario aislado para el adapter (mismo criterio de Task 3) — se cubre por la integración de Task 8.

- [x] Task 5: Excepciones nuevas + `GlobalExceptionHandler` (AC: #1, #4)
  - [x] Nueva `src/main/java/com/auth_service/auth/domain/exception/TargetAccountNotFoundException.java` (Java puro, AD-1) — **distinta** de `AccountNotFoundException` (esa es 401, "mi propio Access Token ya no corresponde a ninguna Cuenta"; esta es 404, "el Administrador consultó un id de Cuenta ajena que no existe" — reutilizar la existente produciría un 401 semánticamente incorrecto para este caso):
    ```java
    package com.auth_service.auth.domain.exception;

    public class TargetAccountNotFoundException extends RuntimeException {

        public TargetAccountNotFoundException(String message) {
            super(message);
        }
    }
    ```
  - [x] Nueva `src/main/java/com/auth_service/auth/domain/exception/SelfManagementNotAllowedException.java`:
    ```java
    package com.auth_service.auth.domain.exception;

    public class SelfManagementNotAllowedException extends RuntimeException {

        public SelfManagementNotAllowedException(String message) {
            super(message);
        }
    }
    ```
  - [x] Añadir a `GlobalExceptionHandler.java`, junto a los demás `@ExceptionHandler`:
    ```java
    @ExceptionHandler(TargetAccountNotFoundException.class)
    public ProblemDetail handleTargetAccountNotFoundException(TargetAccountNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SelfManagementNotAllowedException.class)
    public ProblemDetail handleSelfManagementNotAllowedException(SelfManagementNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
    ```
    (`ex.getMessage()` aquí sí se expone al cliente — a diferencia de `AuthenticationFailedException`/`InvalidRefreshTokenException`, no hay riesgo de enumeración: quien recibe esta respuesta ya es un `ADMIN` autenticado, no un visitante anónimo.)

- [x] Task 6: `ManageAccountUseCase` — orquestación transaccional (AC: #1, #3, #4)
  - [x] Nuevo `src/main/java/com/auth_service/auth/application/usecase/ManageAccountUseCase.java`:
    ```java
    package com.auth_service.auth.application.usecase;

    import com.auth_service.auth.domain.exception.AccountNotFoundException;
    import com.auth_service.auth.domain.exception.DomainValidationException;
    import com.auth_service.auth.domain.exception.SelfManagementNotAllowedException;
    import com.auth_service.auth.domain.exception.TargetAccountNotFoundException;
    import com.auth_service.auth.domain.model.Account;
    import com.auth_service.auth.domain.model.AccountId;
    import com.auth_service.auth.domain.model.AdminAction;
    import com.auth_service.auth.domain.model.AuditLogEntry;
    import com.auth_service.auth.domain.model.AuditResult;
    import com.auth_service.auth.domain.model.Role;
    import com.auth_service.auth.domain.port.AccountRepository;
    import com.auth_service.auth.domain.port.AuditLogRepository;
    import com.auth_service.auth.domain.port.RefreshTokenRepository;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import java.time.Clock;
    import java.time.Instant;
    import java.util.List;
    import java.util.Set;
    import java.util.UUID;
    import java.util.stream.Collectors;

    /**
     * FR-11/FR-13 — único caso de uso que muta Cuentas ajenas por vía
     * administrativa (AD-6, AD-13) y el único que escribe {@code audit_log}
     * (AD-20). {@code AdminController} traduce sus resultados a HTTP, igual
     * que el resto de controllers del proyecto.
     */
    @Service
    public class ManageAccountUseCase {

        private final AccountRepository accountRepository;
        private final RefreshTokenRepository refreshTokenRepository;
        private final AuditLogRepository auditLogRepository;
        private final Clock clock;

        public ManageAccountUseCase(AccountRepository accountRepository, RefreshTokenRepository refreshTokenRepository,
                                     AuditLogRepository auditLogRepository, Clock clock) {
            this.accountRepository = accountRepository;
            this.refreshTokenRepository = refreshTokenRepository;
            this.auditLogRepository = auditLogRepository;
            this.clock = clock;
        }

        public record AccountPage(List<Account> content, int page, int size, long totalElements) {
        }

        @Transactional(readOnly = true)
        public AccountPage listAccounts(int page, int size) {
            if (page < 0) {
                throw new DomainValidationException("page debe ser mayor o igual que 0.");
            }
            if (size < 1 || size > 100) {
                throw new DomainValidationException("size debe estar entre 1 y 100.");
            }
            return new AccountPage(accountRepository.findAllPaged(page, size), page, size, accountRepository.countAll());
        }

        @Transactional(readOnly = true)
        public Account getAccountDetail(String targetAccountId) {
            return findTarget(targetAccountId);
        }

        /**
         * AC #4 / auto-protección: el chequeo compara actor contra target ANTES
         * de invocar {@code target.disable()} — si son la misma Cuenta, se
         * audita el intento rechazado y se lanza sin tocar el estado de la
         * Cuenta ni las Familias de Refresh Tokens.
         *
         * <p>{@code noRollbackFor}: sin esto, la fila de auditoría del intento
         * rechazado (insertada ANTES de lanzar) se revertiría junto con el
         * resto de la transacción al escapar {@link SelfManagementNotAllowedException}
         * — el mismo gotcha que {@code LoginUseCase}/{@code RefreshTokenUseCase}
         * ya documentan para sus propios casos. Si en cambio {@code Account.disable()}
         * lanza {@link DomainValidationException} (Cuenta ya DISABLED), NO hace
         * falta preservar nada: ese chequeo ocurre antes de cualquier escritura,
         * así que el rollback por defecto es correcto ahí.</p>
         */
        @Transactional(noRollbackFor = SelfManagementNotAllowedException.class)
        public Account disableAccount(String actorSubject, String targetAccountId) {
            AccountId actorId = parseActorId(actorSubject);
            Account target = findTarget(targetAccountId);
            Instant now = Instant.now(clock);

            if (actorId.equals(target.id())) {
                auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.DISABLE_ACCOUNT, AuditResult.REJECTED_SELF, now));
                throw new SelfManagementNotAllowedException("Un Administrador no puede desactivarse a sí mismo.");
            }

            target.disable();
            // revokeAllForAccount ANTES de save() (mismo orden que ResetPasswordUseCase,
            // Story 3.1): es un @Modifying(clearAutomatically = true) que limpia el
            // contexto de persistencia — si corriera después del save(), el UPDATE de
            // Hibernate aún sin flush se perdería en silencio.
            refreshTokenRepository.revokeAllForAccount(target.id(), now);
            accountRepository.save(target);
            auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.DISABLE_ACCOUNT, AuditResult.SUCCESS, now));
            return target;
        }

        @Transactional
        public Account reactivateAccount(String actorSubject, String targetAccountId) {
            AccountId actorId = parseActorId(actorSubject);
            Account target = findTarget(targetAccountId);

            target.reactivate();
            accountRepository.save(target);
            auditLogRepository.save(
                    AuditLogEntry.create(actorId, target.id(), AdminAction.REACTIVATE_ACCOUNT, AuditResult.SUCCESS, Instant.now(clock)));
            return target;
        }

        /** Ver Javadoc de {@link #disableAccount} — mismo motivo para {@code noRollbackFor}. */
        @Transactional(noRollbackFor = SelfManagementNotAllowedException.class)
        public Account updateRoles(String actorSubject, String targetAccountId, Set<String> rawRoles) {
            AccountId actorId = parseActorId(actorSubject);
            Account target = findTarget(targetAccountId);
            Set<Role> newRoles = parseRoles(rawRoles);
            Instant now = Instant.now(clock);

            if (actorId.equals(target.id()) && !newRoles.contains(Role.ADMIN)) {
                auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.UPDATE_ROLES, AuditResult.REJECTED_SELF, now));
                throw new SelfManagementNotAllowedException("Un Administrador no puede quitarse el Rol ADMIN a sí mismo.");
            }

            target.updateRoles(newRoles);
            accountRepository.save(target);
            auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.UPDATE_ROLES, AuditResult.SUCCESS, now));
            return target;
        }

        // Mismo patrón defensivo que GetOwnProfileUseCase (Story 1.7): un sub de
        // Access Token que no es UUID no debe terminar en el catch-all 500.
        private AccountId parseActorId(String rawSubject) {
            try {
                return new AccountId(UUID.fromString(rawSubject));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw new AccountNotFoundException("Claim sub del Access Token ausente o no es un UUID.");
            }
        }

        // Un id de Cuenta objetivo malformado en la URL es, desde la perspectiva
        // del llamador, indistinguible de "no existe" — no se le da un 400
        // distinto de formato vs. existencia (mismo principio de no distinguir
        // motivos ya aplicado en otros flujos de esta app, aunque aquí no hay
        // enumeración en juego: es simplicidad de contrato, no anti-enumeración).
        private Account findTarget(String rawTargetId) {
            AccountId targetId;
            try {
                targetId = new AccountId(UUID.fromString(rawTargetId));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw new TargetAccountNotFoundException("Ninguna Cuenta corresponde al id indicado.");
            }
            return accountRepository.findById(targetId)
                    .orElseThrow(() -> new TargetAccountNotFoundException("Ninguna Cuenta corresponde al id indicado."));
        }

        private Set<Role> parseRoles(Set<String> rawRoles) {
            if (rawRoles == null || rawRoles.isEmpty()) {
                throw new DomainValidationException("roles no puede estar vacío.");
            }
            try {
                return rawRoles.stream().map(String::toUpperCase).map(Role::valueOf).collect(Collectors.toSet());
            } catch (IllegalArgumentException invalidRole) {
                throw new DomainValidationException("roles contiene un valor no reconocido.");
            }
        }
    }
    ```
  - [x] Tests nuevos en `ManageAccountUseCaseTest` (unit, mocks de `AccountRepository`/`RefreshTokenRepository`/`AuditLogRepository`, mismo estilo que `LoginUseCaseTest`):
    - `listAccountsReturnsPageFromRepository`.
    - `listAccountsWithNegativePageThrowsDomainValidationException`.
    - `listAccountsWithSizeOutOfRangeThrowsDomainValidationException` (parametrizar `0` y `101`).
    - `getAccountDetailReturnsAccountWhenFound`.
    - `getAccountDetailWithUnknownIdThrowsTargetAccountNotFoundException`.
    - `getAccountDetailWithMalformedIdThrowsTargetAccountNotFoundException`.
    - `disableAccountTransitionsToDisabledRevokesTokensAndAuditsSuccess` — verificar `target.disable()` aplicado, `verify(refreshTokenRepository).revokeAllForAccount(eq(targetId), any())` **antes** de `verify(accountRepository).save(...)` (orden, `InOrder` de Mockito), y `verify(auditLogRepository).save(argThat(e -> e.result() == AuditResult.SUCCESS && e.action() == AdminAction.DISABLE_ACCOUNT))`.
    - `disableAccountOnSelfIsRejectedAuditedAndDoesNotTouchAccountOrTokens` — `actorSubject` == id de la propia Cuenta objetivo → `SelfManagementNotAllowedException`; `verify(accountRepository, never()).save(any())`; `verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any())`; `verify(auditLogRepository).save(argThat(e -> e.result() == AuditResult.REJECTED_SELF))`.
    - `reactivateAccountTransitionsToActiveAndAuditsSuccess`.
    - `updateRolesReplacesRolesAndAuditsSuccess`.
    - `updateRolesRemovingAdminFromSelfIsRejectedAuditedAndDoesNotTouchAccount` — actor == target, `rawRoles = {"USER"}` (sin ADMIN) → `SelfManagementNotAllowedException`, `verify(accountRepository, never()).save(any())`, auditoría `REJECTED_SELF`.
    - `updateRolesRemovingAdminFromAnotherAccountIsAllowed` — actor ≠ target: aunque el nuevo conjunto de roles no incluya ADMIN, se aplica normalmente sin rechazo (la auto-protección de AC #4 solo aplica cuando actor == target).
    - `updateRolesWithEmptySetThrowsDomainValidationExceptionBeforeSelfCheck`.
    - `updateRolesWithUnrecognizedRoleValueThrowsDomainValidationException`.

- [x] Task 7: `AdminController` + DTOs + seguridad por rol (AC: #1, #2, #3, #4)
  - [x] Habilitar `@PreAuthorize` en `SecurityConfig.java` (AD-11 — hasta ahora ningún endpoint lo necesitaba): añadir `@EnableMethodSecurity` a la clase, junto a `@EnableWebSecurity` (import `org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity`). **Corrección descubierta en TDD (contradice el supuesto inicial):** `@PreAuthorize` que falla lanza `AccessDeniedException`/`AuthorizationDeniedException` DENTRO del dispatch de Spring MVC (proxy AOP del controller) — nunca llega a `ExceptionTranslationFilter`, porque el catch-all `@ExceptionHandler(Exception.class)` de `GlobalExceptionHandler` la intercepta primero y la convierte en un 500 genérico. Hubo que añadir un `@ExceptionHandler(AccessDeniedException.class)` explícito en `GlobalExceptionHandler` (403, mismo mensaje fijo `"Access denied."` que `SecurityConfig.accessDeniedHandler()`) — sin este handler, `listWithUserRoleReturns403ProblemJson` fallaba con 500 en vez de 403.
  - [x] Nuevos DTOs en `src/main/java/com/auth_service/auth/infrastructure/controller/dto/`:
    ```java
    // AdminAccountResponse.java — misma forma reutilizada para detalle, disable, reactivate, updateRoles y cada item del listado.
    package com.auth_service.auth.infrastructure.controller.dto;

    import java.time.Instant;
    import java.util.Set;

    public record AdminAccountResponse(String id, String email, Set<String> roles, String status, Instant createdAt) {
    }
    ```
    ```java
    // PagedAccountsResponse.java
    package com.auth_service.auth.infrastructure.controller.dto;

    import java.util.List;

    public record PagedAccountsResponse(List<AdminAccountResponse> content, int page, int size, long totalElements) {
    }
    ```
    ```java
    // UpdateRolesRequest.java
    package com.auth_service.auth.infrastructure.controller.dto;

    import java.util.Set;

    public record UpdateRolesRequest(Set<String> roles) {
    }
    ```
  - [x] Nuevo `src/main/java/com/auth_service/auth/infrastructure/controller/AdminController.java`:
    ```java
    package com.auth_service.auth.infrastructure.controller;

    import com.auth_service.auth.application.usecase.ManageAccountUseCase;
    import com.auth_service.auth.domain.model.Account;
    import com.auth_service.auth.domain.model.Role;
    import com.auth_service.auth.infrastructure.controller.dto.AdminAccountResponse;
    import com.auth_service.auth.infrastructure.controller.dto.PagedAccountsResponse;
    import com.auth_service.auth.infrastructure.controller.dto.UpdateRolesRequest;
    import org.springframework.http.ResponseEntity;
    import org.springframework.security.access.prepost.PreAuthorize;
    import org.springframework.security.core.Authentication;
    import org.springframework.web.bind.annotation.GetMapping;
    import org.springframework.web.bind.annotation.PathVariable;
    import org.springframework.web.bind.annotation.PostMapping;
    import org.springframework.web.bind.annotation.PutMapping;
    import org.springframework.web.bind.annotation.RequestBody;
    import org.springframework.web.bind.annotation.RequestMapping;
    import org.springframework.web.bind.annotation.RequestParam;
    import org.springframework.web.bind.annotation.RestController;

    import java.util.List;
    import java.util.stream.Collectors;

    /** FR-11/FR-13 — todos los endpoints exigen Rol ADMIN (AD-11, AD-18). */
    @RestController
    @RequestMapping("/api/v1/admin/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public class AdminController {

        private final ManageAccountUseCase manageAccountUseCase;

        public AdminController(ManageAccountUseCase manageAccountUseCase) {
            this.manageAccountUseCase = manageAccountUseCase;
        }

        @GetMapping
        public ResponseEntity<PagedAccountsResponse> list(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
            ManageAccountUseCase.AccountPage result = manageAccountUseCase.listAccounts(page, size);
            List<AdminAccountResponse> content = result.content().stream().map(this::toResponse).toList();
            return ResponseEntity.ok(new PagedAccountsResponse(content, result.page(), result.size(), result.totalElements()));
        }

        @GetMapping("/{id}")
        public ResponseEntity<AdminAccountResponse> detail(@PathVariable String id) {
            return ResponseEntity.ok(toResponse(manageAccountUseCase.getAccountDetail(id)));
        }

        @PostMapping("/{id}/disable")
        public ResponseEntity<AdminAccountResponse> disable(@PathVariable String id, Authentication authentication) {
            return ResponseEntity.ok(toResponse(manageAccountUseCase.disableAccount(authentication.getName(), id)));
        }

        @PostMapping("/{id}/reactivate")
        public ResponseEntity<AdminAccountResponse> reactivate(@PathVariable String id, Authentication authentication) {
            return ResponseEntity.ok(toResponse(manageAccountUseCase.reactivateAccount(authentication.getName(), id)));
        }

        @PutMapping("/{id}/roles")
        public ResponseEntity<AdminAccountResponse> updateRoles(@PathVariable String id, @RequestBody UpdateRolesRequest request,
                                                                  Authentication authentication) {
            return ResponseEntity.ok(toResponse(manageAccountUseCase.updateRoles(authentication.getName(), id, request.roles())));
        }

        private AdminAccountResponse toResponse(Account account) {
            return new AdminAccountResponse(account.id().value().toString(), account.email().value(),
                    account.roles().stream().map(Role::name).collect(Collectors.toSet()), account.status().name(),
                    account.createdAt());
        }
    }
    ```
  - [x] Tests nuevos `AdminControllerTest` (`@WebMvcTest(controllers = AdminController.class)`, mismo patrón que `UserControllerTest`: `@Import({SecurityConfig.class, JwtAuthenticationFilter.class})`, JWT real construido con `Jwts.builder()`, `@MockitoBean ManageAccountUseCase` + los mismos colaboradores OAuth2 mockeados que `UserControllerTest` porque `SecurityConfig` los exige):
    - `listWithAdminRoleReturns200AndPagedBody`.
    - `listWithUserRoleReturns403ProblemJson` — token con `claim("roles", List.of("USER"))` → 403, `verify(manageAccountUseCase, never()).listAccounts(anyInt(), anyInt())`.
    - `detailWithAdminRoleReturns200`.
    - `detailWithUnknownIdReturns404ProblemJson` — `when(manageAccountUseCase.getAccountDetail(any())).thenThrow(new TargetAccountNotFoundException(...))`.
    - `disableReturns200WithUpdatedAccount`.
    - `disableOnSelfReturns409ProblemJson` — `when(manageAccountUseCase.disableAccount(any(), any())).thenThrow(new SelfManagementNotAllowedException(...))`.
    - `updateRolesReturns200WithUpdatedAccount`.
    - `missingAuthorizationHeaderReturns401ProblemJson` (mismo patrón que `UserControllerTest`).

- [x] Task 8: Tests de integración `AdminAccountsIntegrationTest` (AC: #1, #2, #3, #4, #5)
  - [x] Nuevo `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAccountsIntegrationTest.java` — mismo patrón Testcontainers que `AuthLoginIntegrationTest`/`AuthLoginLockoutIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureMockMvc`, `@Testcontainers(disabledWithoutDocker = true)`, `@Transactional`). El Access Token `ADMIN` se obtiene creando directamente una Cuenta `ADMIN` vía `Account.registerAdmin(...)` + `accountRepository.save(...)` (Story 4.1) y firmando un JWT de test con `claim("roles", List.of("ADMIN", "USER"))` — **no** vía `POST /auth/login` real, para no acoplar este test al flujo de login (mismo criterio que otros tests de integración que ya construyen su propio JWT en vez de autenticarse extremo a extremo cuando el login en sí no es lo que se está probando).
    - `adminListsAccountsPaginated` — persistir 3 Cuentas + la propia `ADMIN` → GET `?page=0&size=2` devuelve 2 elementos y `totalElements == 4`.
    - `userRoleGetsForbiddenOnAnyAdminEndpoint` — token con solo `USER` → 403 en GET `/api/v1/admin/accounts`.
    - `adminDisablesActiveAccountRevokesTokensAndWritesAuditLog` — persistir Cuenta objetivo `ACTIVE` + un Refresh Token vigente suyo (vía `RefreshTokenRepository`) → POST `.../disable` → 200, `accountRepository.findById(...).status() == DISABLED`, el Refresh Token queda con `revokedAt` no nulo, y una fila en `audit_log` (vía `AuditLogJpaRepository` autowireado) con `action=DISABLE_ACCOUNT`, `result=SUCCESS`.
    - `disabledAccountCannotLoginWithCredentials` — tras desactivar, `POST /auth/login` con las credenciales correctas de esa Cuenta devuelve 401 (reutiliza el guard `status != ACTIVE` ya existente en `LoginUseCase`, sin cambios en esta historia).
    - `adminReactivatesDisabledAccountAndWritesAuditLog`.
    - `adminCannotDisableSelfAndAttemptIsAudited` — POST `.../disable` sobre el propio id del ADMIN autenticado → 409, Cuenta del ADMIN sigue `ACTIVE`, fila `audit_log` con `result=REJECTED_SELF`.
    - `adminCannotRemoveOwnAdminRoleAndAttemptIsAudited` — PUT `.../roles` con `{"roles":["USER"]}` sobre el propio id → 409, roles del ADMIN sin cambios, fila `audit_log` con `result=REJECTED_SELF`.
    - `auditLogRejectsDirectUpdateAndDeleteAtDatabaseLevel` — usando `@PersistenceContext EntityManager` (o `JdbcTemplate`) ejecutar un `UPDATE audit_log SET result = 'SUCCESS' WHERE id = ...` / `DELETE FROM audit_log WHERE id = ...` nativo directamente contra Postgres y verificar que lanza (el trigger de Task 3 lo rechaza) — prueba AC #5 a nivel de esquema, no solo de código de aplicación.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Epics 1, 2, 3 y Story 4.1.

### Review Findings

- [x] [Review][Patch] `reactivateAccount` no tiene guarda de auto-gestión, a diferencia de `disableAccount`/`updateRoles` — Un Administrador desactivado por otro Administrador conserva su Access Token vigente (solo se revocan Refresh Tokens) y podría llamar `POST .../reactivate` sobre sí mismo, deshaciendo unilateralmente la acción disciplinaria de otro Admin. Aplicado (2026-07-21): misma auto-protección de `disable`/`updateRoles` extendida a `reactivate` (chequeo `actorId == target.id()` → `SelfManagementNotAllowedException` + auditoría `REJECTED_SELF`), con test unitario `reactivateAccountOnSelfIsRejectedAuditedAndDoesNotTouchAccount`. [ManageAccountUseCase.java (reactivateAccount)]
- [x] [Review][Patch] `parseRoles` lanza `NullPointerException` no controlada si el Set de roles recibido contiene un elemento `null` (p. ej. `{"roles":["ADMIN", null]}`), devolviendo 500 en vez del 400 esperado [ManageAccountUseCase.java:166]
- [x] [Review][Patch] `parseRoles` no hace `trim()` de cada valor antes de `Role.valueOf` — un rol con espacios (`" ADMIN"`) falla con `DomainValidationException` en vez de normalizarse [ManageAccountUseCase.java:166]
- [x] [Review][Patch] `AccountRepositoryAdapter.findAllPaged` construye `PageRequest.of(page, size)` sin `Sort` — el orden de filas no es determinista entre páginas bajo inserciones/eliminaciones concurrentes, pudiendo saltar o duplicar cuentas al paginar. Aplicado (2026-07-21): orden determinista por `createdAt` + `id` [AccountRepositoryAdapter.java:48]
- [x] [Review][Patch] El trigger append-only de `audit_log` solo cubre `BEFORE UPDATE OR DELETE FOR EACH ROW` — PostgreSQL no dispara triggers de fila ante `TRUNCATE`, así que un `TRUNCATE audit_log` borraría todo el historial pese a la garantía "append-only" que la propia migración documenta extensamente. Aplicado (2026-07-21): trigger adicional `FOR EACH STATEMENT BEFORE TRUNCATE` [V5__audit_log.sql]
- [x] [Review][Patch] La aserción de paridad 403 en el Javadoc de `GlobalExceptionHandler` ("mismo cuerpo que el filtro deny-all") no está verificada por ningún test — el test existente solo comprueba `status().isForbidden()`, no el contenido del cuerpo. Aplicado (2026-07-21): `listWithUserRoleReturns403ProblemJson` ahora también verifica `$.detail` [GlobalExceptionHandler.java]
- [x] [Review][Patch] El test `malformedActorSubjectThrowsAccountNotFoundException` no verifica ausencia de efectos secundarios (ni `accountRepository.save` ni `auditLogRepository.save` deberían invocarse en ese camino). Aplicado (2026-07-21) [ManageAccountUseCaseTest.java]
- [x] [Review][Patch/Bug adicional descubierto en verificación] `disabledAccountCannotLoginWithCredentials` fallaba con 500 en vez de 401 al correr la suite real contra Testcontainers (ninguna de las 3 capas de revisión estática lo detectó, ya que no ejecutan código). Causa: `AccountEntity`/`AuditLogEntity` usan IDs asignados manualmente → Spring Data JPA usa `merge()` en vez de `persist()` → el INSERT de la cuenta del Admin queda diferido; como `audit_log` no tiene relación JPA mapeada hacia `accounts` (columna UUID cruda), Hibernate no sabe que debe insertar la cuenta antes que la fila de auditoría al flushear. Es un artefacto de que el test envuelve dos requests HTTP en una sola transacción de test nunca-commiteada (mismo patrón ya documentado para Story 4.1 en deferred-work.md) — en producción cada request real es su propia transacción y esto no ocurre. Aplicado (2026-07-21): `entityManager.flush()` en los helpers `persistAdmin`/`persistActiveAccount` de `AdminAccountsIntegrationTest`, forzando el INSERT antes de que el request dependiente corra. [AdminAccountsIntegrationTest.java]
- [x] [Review][Patch] El test `auditLogRejectsDirectUpdateAndDeleteAtDatabaseLevel` (nombre y Task 8 indican UPDATE y DELETE) solo ejercitaba el `UPDATE` nativo — el `DELETE` nativo especificado explícitamente en la Task 8 nunca se probaba. Aplicado (2026-07-21): separado en dos tests independientes, `auditLogRejectsDirectUpdateAtDatabaseLevel` y `auditLogRejectsDirectDeleteAtDatabaseLevel` — no pueden compartir una transacción porque Postgres envenena la transacción JDBC completa tras el primer rechazo (y el `JpaDialect` del proyecto no soporta savepoints/`PROPAGATION_NESTED`). [AdminAccountsIntegrationTest.java]
- [x] [Review][Defer] `listAccounts` ejecuta `findAllPaged` y `countAll` como dos lecturas independientes bajo `READ_COMMITTED` — `totalElements` puede no coincidir con `content` si se inserta/elimina una cuenta entre ambas consultas [ManageAccountUseCase.java:59] — deferred, pre-existing
- [x] [Review][Defer] Ninguna transición de `Account` (`disable`/`reactivate`/`updateRoles`) tiene locking optimista (`@Version`) — dos Admins concurrentes sobre la misma Cuenta pueden producir una actualización perdida, aunque ambas queden auditadas como `SUCCESS`; misma categoría de riesgo ya diferida en Stories 3.2 y 4.1 [Account.java] — deferred, pre-existing
- [x] [Review][Defer] Las FK de `audit_log` (`actor_account_id`/`target_account_id` → `accounts.id`) no declaran `ON DELETE` — si en el futuro se introduce borrado físico de cuentas, cualquier cuenta mencionada en auditoría quedaría imposible de borrar; sin flujo de borrado físico hoy [V5__audit_log.sql] — deferred, pre-existing
- [x] [Review][Defer] Sin índice en `audit_log` para `target_account_id`/`occurred_at` — la próxima historia (4-3, consulta del registro de auditoría) tropezará con esto; fuera de alcance de esta historia [V5__audit_log.sql] — deferred, pre-existing

## Dev Notes

- **Endpoints y verbos no están fijados literalmente por ningún AC de epics.md/PRD** — el PRD (FR-11) y epics.md (Story 4.2) describen las capacidades ("listar, consultar, desactivar/reactivar, modificar Roles") en Given/When/Then pero no fijan rutas ni verbos exactos más allá de `GET /api/v1/admin/accounts` y `GET /api/v1/admin/accounts/{id}` (esos dos sí están literales en el epic). Las rutas de mutación (`POST .../disable`, `POST .../reactivate`, `PUT .../roles`) son una decisión de esta historia, siguiendo AD-18 (`/api/v1/admin/**`) y la convención REST de acciones explícitas para transiciones de estado que no son un simple reemplazo de recurso — no inventar un DTO de "PATCH status genérico", mantener las tres operaciones explícitas y auditables por separado.
- **Auto-protección (AC #4) es responsabilidad de `ManageAccountUseCase`, no de `Account`:** `Account` no sabe quién es "sí mismo" — comparar actor vs. target requiere el id del solicitante, algo ajeno al propio agregado (AD-6: la decisión de cuándo aplicar una transición vive en `application/usecase`). No añadir un parámetro "actorId" a `Account.disable()`/`updateRoles()` — mezclaría una preocupación de autorización dentro del dominio.
- **El intento de auto-protección rechazado TAMBIÉN se audita (AC #4)** — no es un detalle menor: la fila de auditoría debe persistir en la MISMA transacción que lanza `SelfManagementNotAllowedException`, lo que exige `@Transactional(noRollbackFor = SelfManagementNotAllowedException.class)` en `disableAccount`/`updateRoles` (mismo patrón ya usado dos veces en este proyecto — `LoginUseCase` con `AuthenticationFailedException`, `RefreshTokenUseCase` con `InvalidRefreshTokenException`). Sin esto, Spring revertiría la propia fila de auditoría que se intenta preservar, dejando el intento rechazado sin rastro pese al AC.
- **`audit_log` append-only es de esquema, no solo de código (AC #5, addendum "se protege a nivel de rol de PostgreSQL, no solo de código"):** este proyecto tiene un único rol de PostgreSQL (`DB_USER`, ver `docker-compose.yml`/`.env.example`), que es a la vez quien ejecuta las migraciones (dueño de las tablas) y quien correría en runtime — un `REVOKE UPDATE, DELETE ON audit_log FROM <rol>` clásico **no tiene efecto** sobre el dueño de una tabla en PostgreSQL (el dueño conserva sus privilegios DML implícitos pese a cualquier REVOKE sobre sí mismo). Por eso Task 3 usa un trigger `BEFORE UPDATE OR DELETE` que rechaza incondicionalmente — es la única garantía real dentro de la topología de un solo rol que ya tiene este proyecto. Separar un segundo rol de aplicación con menos privilegio que el rol propietario de las migraciones sería la alternativa "de manual", pero implica tocar aprovisionamiento de roles en `docker-compose`/README — fuera de alcance, referenciar en `deferred-work.md` si se decide revisitar.
- **`AccountRepository.findAllPaged`/`countAll` usan `int`/`long` planos, no `Pageable`/`Page` de Spring Data, en la firma del port** — `domain/port` es Java puro (AD-1); `ArchitectureRulesTest.domain_should_be_framework_free` rompe el build si algo en `domain/` importa `org.springframework..`. Spring Data's `Pageable`/`Page` solo aparecen dentro de `AccountRepositoryAdapter` (`infrastructure/`), nunca cruzan hacia `domain/` ni `application/`.
- **Reutilizar el guard `status != ACTIVE` ya existente, no duplicarlo:** `LoginUseCase`, `FederatedLoginUseCase` y `RefreshTokenUseCase` (Stories 1.4/2.1/1.5, reforzadas en Story 3.2/4.1) ya rechazan cualquier Cuenta que no esté `ACTIVE`, incluida `DISABLED`. Desactivar una Cuenta en esta historia solo necesita transicionar `status` + revocar Refresh Tokens — el "no puede autenticarse por ninguna vía" del AC #3 ya queda cubierto sin tocar esos tres casos de uso.
- **`revokeAllForAccount` antes de `save()`** — mismo orden y mismo motivo que `ResetPasswordUseCase` (Story 3.1, ver su código): es un `@Modifying(clearAutomatically = true)`, así que debe ejecutarse antes de cualquier `save()` de la misma transacción o el cambio de estado se perdería en silencio del contexto de persistencia.
- **`@EnableMethodSecurity` es nuevo en este proyecto** — hasta esta historia, ninguna ruta necesitaba autorización por Rol más allá del deny-all genérico de `SecurityConfig` (autenticado sí/no). `@PreAuthorize("hasRole('ADMIN')")` a nivel de clase en `AdminController` es más simple que repetirlo en cada método y sigue el mismo AD-11 que ya menciona explícitamente este mecanismo para FR-11/FR-13.
- **Por qué `AccountId`/target parseados devuelven "no encontrado" en vez de "formato inválido" para el id de la URL:** a diferencia de `Email`/`RawPassword` (Value Objects con formato de negocio real que amerita un 400 distinguible, AD-14), un `AccountId` es un UUID interno sin significado para el cliente — no hay AC que pida distinguir "mal formado" de "no existe", y tratarlos igual (404) es más simple sin perder nada observable.
- **Por qué el propio Administrador puede quitar el Rol ADMIN de OTRA Cuenta libremente (sin auto-protección)** — el AC #4 solo prohíbe la auto-desactivación y la auto-remoción de ADMIN; degradar a otro Administrador es una decisión administrativa legítima (dos Administradores pueden coordinarse fuera de banda) y no hay ningún AC que lo restrinja. No inventar una regla de "no dejar el sistema sin ningún ADMIN" — no está pedida y sería scope creep (si se necesita en el futuro, sería una historia propia con su propio AC).
- **Sin cambio en `LoginUseCase`/`FederatedLoginUseCase`/`RefreshTokenUseCase`/`AuthController`/`UserController`** — todos sus guards de `status` ya existentes son suficientes; esta historia solo añade el punto de entrada administrativo que dispara la transición.

### Project Structure Notes

- **Nuevos:**
  - `src/main/java/com/auth_service/auth/domain/model/AdminAction.java`
  - `src/main/java/com/auth_service/auth/domain/model/AuditResult.java`
  - `src/main/java/com/auth_service/auth/domain/model/AuditLogEntry.java`
  - `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java`
  - `src/main/java/com/auth_service/auth/domain/exception/TargetAccountNotFoundException.java`
  - `src/main/java/com/auth_service/auth/domain/exception/SelfManagementNotAllowedException.java`
  - `src/main/java/com/auth_service/auth/application/usecase/ManageAccountUseCase.java`
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogEntity.java`
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java`
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/AdminController.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/dto/AdminAccountResponse.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/dto/PagedAccountsResponse.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/dto/UpdateRolesRequest.java`
  - `src/main/resources/db/migration/V5__audit_log.sql`
  - `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+ tests de `disable`/`reactivate`/`updateRoles`, ya existe — solo se añaden tests)
  - `src/test/java/com/auth_service/auth/application/usecase/ManageAccountUseCaseTest.java`
  - `src/test/java/com/auth_service/auth/infrastructure/controller/AdminControllerTest.java`
  - `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAccountsIntegrationTest.java`
- **Modificados:**
  - `src/main/java/com/auth_service/auth/domain/model/Account.java` (+`disable`, `+reactivate`, `+updateRoles`)
  - `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java` (+`findAllPaged`, `+countAll`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java` (+implementación de `findAllPaged`/`countAll`)
  - `src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java` (+handlers de `TargetAccountNotFoundException` 404, `SelfManagementNotAllowedException` 409, `AccessDeniedException` 403 — este último no estaba previsto originalmente, ver Dev Notes/Task 7)
  - `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (+`@EnableMethodSecurity`)
- **Sin cambios:** `AuthController`, `UserController`, `LoginUseCase`, `FederatedLoginUseCase`, `RefreshTokenUseCase` (sus guards de `status` ya existentes bastan), `AccountJpaRepository` (no necesita método nuevo — `findAll(Pageable)`/`count()` ya vienen heredados de `JpaRepository`), `AdminBootstrapRunner`/`ProvisionInitialAdminUseCase` (Story 4.1, sin relación).
- **Nueva migración:** `V5__audit_log.sql` (tabla `audit_log` + trigger append-only).

### References

- [Source: docs/planning-artifacts/epics.md#Epic-4] — Story 4.2 completa (user story + AC Given/When/Then), FR-11/FR-13
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-11] — "Gestión administrativa de Cuentas": auto-protección y listado paginado como `[ASSUMPTION]`
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-13] — "Auditoría de acciones administrativas": append-only, filtros mínimos (Story 4.3, no esta historia)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md] — "tabla audit_log sin permisos UPDATE/DELETE para el rol de aplicación de BD (se protege a nivel de rol de PostgreSQL, no solo de código)" — motiva la decisión de trigger de Task 3
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-4] — desactivación revoca todas las Familias de Refresh Tokens
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-6] — mutación solo desde `application/usecase`; transiciones de `AccountStatus` nunca en `infrastructure/`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-11] — deny-all, `@PreAuthorize("hasRole('ADMIN')")` explícitamente mencionado para FR-11/FR-13
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-18] — `/api/v1/admin/**`, versionado explícito
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-20] — `audit_log` append-only, mismo transacción que la mutación
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Capability→Architecture-Map] — "FR-11 Administración | AdminController → ManageAccountUseCase | AD-4, AD-6, AD-11, AD-13, AD-18, AD-20"; "FR-13 | AUDIT_LOG, escrito por ManageAccountUseCase | AD-20"
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#erDiagram] — forma de `AUDIT_LOG` (id, actor_account_id, target_account_id, action, result, occurred_at)
- [Source: src/main/java/com/auth_service/auth/domain/model/Account.java] — `activate()` como patrón exacto a replicar para `disable`/`reactivate` (valida transición, lanza `DomainValidationException`)
- [Source: src/main/java/com/auth_service/auth/application/usecase/ResetPasswordUseCase.java] — orden `revokeAllForAccount` antes de `save()`, replicado en `disableAccount`
- [Source: src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] y [Source: src/main/java/com/auth_service/auth/application/usecase/RefreshTokenUseCase.java] — patrón `@Transactional(noRollbackFor = ...)` replicado para preservar la auditoría del intento rechazado
- [Source: src/main/java/com/auth_service/auth/application/usecase/GetOwnProfileUseCase.java] — parseo defensivo de `sub` (UUID.fromString con catch de IllegalArgumentException/NullPointerException), replicado para `parseActorId`
- [Source: src/main/java/com/auth_service/auth/infrastructure/controller/UserController.java] — patrón controller→use case→DTO a replicar para `AdminController`
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/security/JwtAuthenticationFilter.java] — cómo se autentica y de dónde salen las `GrantedAuthority` `ROLE_*` que `@PreAuthorize("hasRole('ADMIN')")` evalúa
- [Source: src/main/java/com/auth_service/auth/config/SecurityConfig.java] — deny-all existente, dónde añadir `@EnableMethodSecurity`
- [Source: src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java] — patrón de `@ExceptionHandler` a replicar para las dos excepciones nuevas
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountEntity.java] y [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountJpaRepository.java] — patrón de entidad/repo JPA a replicar para `AuditLogEntity`/`AuditLogJpaRepository`; confirma que `JpaRepository` ya trae `findAll(Pageable)`/`count()` sin tocar el repo de Spring Data
- [Source: src/main/resources/db/migration/V4__federated_identities.sql] — última migración existente, `V5` es la siguiente
- [Source: docker-compose.yml] y [Source: .env.example] — `DB_USER`/`POSTGRES_USER` como único rol de PostgreSQL del proyecto, motiva la decisión de trigger sobre REVOKE en Task 3
- [Source: src/test/java/com/auth_service/auth/infrastructure/controller/UserControllerTest.java] — patrón de test slice `@WebMvcTest` + JWT real construido a mano (no `spring-security-test`), replicado para `AdminControllerTest`
- [Source: src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginLockoutIntegrationTest.java] y [Source: src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest.java] — patrón Testcontainers a replicar para `AdminAccountsIntegrationTest`
- [Source: src/test/java/com/auth_service/auth/domain/model/AccountTest.java] — patrón de test de dominio (incluye `@ParameterizedTest @EnumSource`) a replicar para los tests de `disable`/`reactivate`/`updateRoles`
- [Source: docs/implementation-artifacts/4-1-aprovisionamiento-del-primer-administrador.md] — plantilla de formato/nivel de detalle de esta historia; patrón `registerAdmin`/`existsByRole` como precedente de "no añadir más superficie de la que el AC pide" replicado en el port de auditoría (solo `save`)
- [Source: docs/implementation-artifacts/sprint-status.yaml] — Epic 4: Story 4.1 `done`, ninguna otra dependencia pendiente para esta historia

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -Dtest=AccountTest test` — 30/30 verde tras Task 1 (`disable`/`reactivate`/`updateRoles`).
- `./mvnw -q -o compile` — verde tras Task 2 (`AuditLogEntry`, `AdminAction`, `AuditResult`, `AuditLogRepository`).
- `./mvnw -q -o compile` — verde tras Task 3 (`AuditLogEntity`/`AuditLogJpaRepository`/`AuditLogRepositoryAdapter`, `V5__audit_log.sql`).
- `./mvnw -Dtest=ArchitectureRulesTest test` — 3/3 verde tras Task 4 (`findAllPaged`/`countAll` en el port sin romper `domain_should_be_framework_free`).
- `./mvnw -q -o compile` — verde tras Task 5 (`TargetAccountNotFoundException`/`SelfManagementNotAllowedException` + handlers).
- `./mvnw -Dtest=ManageAccountUseCaseTest test` — 16/16 verde tras Task 6.
- `./mvnw -Dtest=AdminControllerTest test` — 8/8 verde tras Task 7. Primer intento con `listWithUserRoleReturns403ProblemJson` falló (500 en vez de 403) — ver Completion Notes, hallazgo de `@PreAuthorize`/`GlobalExceptionHandler`.
- `./mvnw -Dtest=AdminAccountsIntegrationTest test` — 8/8 **omitidos** (sin Docker disponible en este entorno; `@Testcontainers(disabledWithoutDocker = true)` los salta en vez de fallar). Compilan y no hay forma de ejercitarlos contra PostgreSQL real en esta sesión — mismo límite ya documentado en la Story 4.1.
- `./mvnw -o test` (suite completa) — **324/324 tests verdes, 66 omitidos (Testcontainers, sin Docker), `BUILD SUCCESS`**, incluyendo `ArchitectureRulesTest` y todos los `*IntegrationTest`/`*ControllerTest` de Epics 1, 2, 3 y Story 4.1 sin regresión.

### Completion Notes List

- Las 5 AC están implementadas: (1) listado paginado + detalle vía `GET /api/v1/admin/accounts[/{id}]`, cubierto por `ManageAccountUseCaseTest`/`AdminControllerTest`/`AdminAccountsIntegrationTest.adminListsAccountsPaginated`; (2) Rol `USER` recibe 403 en cualquier endpoint admin, cubierto por `listWithUserRoleReturns403ProblemJson`/`userRoleGetsForbiddenOnAnyAdminEndpoint`; (3) desactivación (revoca tokens + audita) y reactivación (audita), cubiertas por `disableAccountTransitionsToDisabledRevokesTokensAndAuditsSuccess` y los tests de integración correspondientes, incluyendo que la Cuenta desactivada no puede loguearse (`disabledAccountCannotLoginWithCredentials`, reutilizando el guard `status != ACTIVE` ya existente sin tocarlo); (4) auto-protección (auto-desactivación y auto-remoción de ADMIN) rechazada con 409 y auditada como `REJECTED_SELF`, cubierta en las tres capas de test; (5) `audit_log` append-only garantizado por trigger de PostgreSQL (no por `REVOKE`, ver Dev Notes de la story), verificado en `auditLogRejectsDirectUpdateAndDeleteAtDatabaseLevel`.
- **Hallazgo de implementación que corrige un supuesto de la story (Task 7):** el Dev Notes original asumía que un `AccessDeniedException` de `@PreAuthorize` remontaría a través de `ExceptionTranslationFilter` hasta el `accessDeniedHandler()` de `SecurityConfig` sin tocar `GlobalExceptionHandler`. En la práctica, la excepción se lanza DENTRO del dispatch de Spring MVC (proxy AOP del controller) y el catch-all `@ExceptionHandler(Exception.class)` ya existente la intercepta primero, devolviendo un 500 genérico en vez de 403 — descubierto por el test `listWithUserRoleReturns403ProblemJson` en rojo. Se añadió un `@ExceptionHandler(AccessDeniedException.class)` explícito en `GlobalExceptionHandler` (403, mismo mensaje `"Access denied."` que el handler de `SecurityConfig`, para que ambos caminos sean indistinguibles para el cliente). La story se actualizó para reflejar esta corrección.
- Ningún otro ajuste de diseño respecto a la story: los endpoints, DTOs, excepciones, migración, entidad/adapter de auditoría y `ManageAccountUseCase` se implementaron tal cual estaban especificados, incluyendo la decisión de trigger append-only (no `REVOKE`) para `audit_log`.
- No hizo falta ningún cambio en `LoginUseCase`/`FederatedLoginUseCase`/`RefreshTokenUseCase`/`AuthController`/`UserController`/`AccountJpaRepository`, confirmando las notas de "Project Structure Notes" de la story.
- Sin Docker disponible en este entorno de desarrollo, los 8 tests de `AdminAccountsIntegrationTest` compilan y se omiten automáticamente (mismo patrón que el resto de la suite); no se pudo verificar su ejecución real contra PostgreSQL en esta sesión.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/domain/model/AdminAction.java`
- `src/main/java/com/auth_service/auth/domain/model/AuditResult.java`
- `src/main/java/com/auth_service/auth/domain/model/AuditLogEntry.java`
- `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java`
- `src/main/java/com/auth_service/auth/domain/exception/TargetAccountNotFoundException.java`
- `src/main/java/com/auth_service/auth/domain/exception/SelfManagementNotAllowedException.java`
- `src/main/java/com/auth_service/auth/application/usecase/ManageAccountUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogEntity.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/AdminController.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/AdminAccountResponse.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/PagedAccountsResponse.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/UpdateRolesRequest.java`
- `src/main/resources/db/migration/V5__audit_log.sql`
- `src/test/java/com/auth_service/auth/application/usecase/ManageAccountUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AdminControllerTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAccountsIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (+`disable`, `+reactivate`, `+updateRoles`)
- `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java` (+`findAllPaged`, `+countAll`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java` (+implementación de `findAllPaged`/`countAll`)
- `src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java` (+handlers de `TargetAccountNotFoundException` 404, `SelfManagementNotAllowedException` 409, `AccessDeniedException` 403)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (+`@EnableMethodSecurity`)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+tests de `disable`/`reactivate`/`updateRoles`)
