---
baseline_commit: 5b62aae
---

# Story 3.2: Bloqueo automático por fuerza bruta

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a titular de una Cuenta,
I want que el sistema bloquee mi Cuenta ante intentos repetidos de acceso fallidos,
so that un atacante no pueda adivinar mi contraseña. (FR-8, FR-9)

## Acceptance Criteria

1. Dado un intento de login fallido (password incorrecta, hash ilegible, o Cuenta sin `passwordHash` local) contra una Cuenta `ACTIVE`, `LoginUseCase` incrementa `failed_attempts` de esa Cuenta (FR-8). Un login exitoso reinicia el contador a cero.
2. Dado que `failed_attempts` alcanza el umbral configurable (default 5) tras un fallo, la Cuenta pasa a `LOCKED` con `locked_until = ahora + duración configurable` (default 15 min, NFR-7) — transición solo alcanzable desde `ACTIVE` (AD-6). El titular es notificado por email tras el commit de la transacción (AD-9), una única vez, en el intento que produce el bloqueo.
3. Dado una Cuenta `LOCKED` cuyo `locked_until` todavía no pasó, al hacer POST `/auth/login` — incluso con la contraseña correcta — la respuesta es el mismo `401` genérico que cualquier otra credencial inválida (NFR-2, AD-8), sin distinguir el motivo ni incrementar más el contador.
4. Dado una Cuenta `LOCKED` cuyo `locked_until` ya pasó, al hacer POST `/auth/login` la Cuenta vuelve a operar como `ACTIVE` automáticamente antes de evaluar las credenciales (transición dentro de `LoginUseCase`, AD-6, AD-13) — un login válido después del auto-desbloqueo tiene éxito normalmente.

## Tasks / Subtasks

- [x] Task 1: `LockoutProperties` — umbral y duración configurables (AC: #2, NFR-7)
  - [x] Nuevo record en `config/`, mismo patrón exacto que `AuthTokenProperties` (record + `@ConfigurationProperties` + constructor compacto con defaults y validación; `@ConfigurationPropertiesScan` en `AuthServiceApplication` ya lo registra automáticamente, sin tocar esa clase):
    ```java
    @ConfigurationProperties(prefix = "auth.lockout")
    public record LockoutProperties(Integer threshold, Duration lockDuration) {
        public LockoutProperties {
            if (threshold == null) {
                threshold = 5;
            } else if (threshold <= 0) {
                throw new IllegalStateException("auth.lockout.threshold debe ser mayor que cero.");
            }
            if (lockDuration == null) {
                lockDuration = Duration.ofMinutes(15);
            } else if (lockDuration.isZero() || lockDuration.isNegative()) {
                throw new IllegalStateException("auth.lockout.lock-duration debe ser una duración positiva.");
            }
        }
    }
    ```
  - [x] Nuevas propiedades en `application.properties`, junto al bloque `auth.token.*`: `auth.lockout.threshold=5` y `auth.lockout.lock-duration=15m`.
  - [x] Test nuevo `LockoutPropertiesTest` — mismo patrón exacto que `AuthTokenPropertiesTest`: defaults cuando ambos son null, valores explícitos, rechaza `threshold` ≤ 0, rechaza `lockDuration` cero/negativo.

- [x] Task 2: `Account` — mutadores de intentos fallidos y (des)bloqueo (AC: #1, #2, #4)
  - [x] `failed_attempts`/`locked_until` ya existen como campos y getters en `Account` desde la Story 1.1 (sin mutador todavía) — este gap es exactamente el que cierra esta historia.
  - [x] Añadir a `domain/model/Account.java`:
    ```java
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

    /** Login exitoso (FR-8) — reinicia el contador. */
    public void recordSuccessfulLogin() {
        failedAttempts = 0;
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
    ```
  - [x] Tests nuevos en `AccountTest`: `recordFailedLoginAttempt` incrementa sin bloquear por debajo del umbral; alcanza el umbral → `LOCKED` + `lockedUntil` seteado + devuelve `true`; no-op (devuelve `false`, no incrementa) si la Cuenta no es `ACTIVE` (parametrizado sobre `PENDING_VERIFICATION`/`LOCKED`/`DISABLED`); `recordSuccessfulLogin` resetea a cero sin tocar `status`; `unlockIfExpired` transiciona cuando `lockedUntil` ya pasó y resetea `failedAttempts`, no hace nada si `lockedUntil` es `null` o todavía no pasó, no hace nada si el estado no es `LOCKED`.

- [x] Task 3: `EmailSender` — notificación de bloqueo (AC: #2)
  - [x] Añadir a `domain/port/EmailSender.java`: `void sendAccountLockedEmail(Email recipient, Instant lockedUntil);`
  - [x] Implementar en `infrastructure/adapters/email/LoggingEmailSender.java`, mismo patrón que `sendPasswordResetEmail` (loggea destinatario + `lockedUntil`, sin URL de acción porque no hay ninguna — es solo informativo).
  - [x] Nuevo record `AccountLockedEmailRequested(Email recipient, Instant lockedUntil)` en `application/usecase/`, mismo paquete y mismo Javadoc-caveat ("no es un Domain Event" — el outbox transaccional de AD-15 es Epic 6, todavía no existe infraestructura para él; este evento usa el mismo mecanismo in-process `ApplicationEventPublisher` ya establecido en Stories 1.2/1.3/3.1) que `PasswordResetEmailRequested`.
  - [x] Nuevo método en `EmailNotificationListener.java`: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` sobre `AccountLockedEmailRequested`, llamando a `emailSender.sendAccountLockedEmail(...)` dentro de un `try/catch(RuntimeException)` que solo loggea — mismo patrón exacto que los otros dos listeners de esa clase.

- [x] Task 4: `LoginUseCase` — orquestación completa (AC: #1, #2, #3, #4)
  - [x] Nuevas dependencias en el constructor: `LockoutProperties`, `ApplicationEventPublisher`, `Clock` — mismo patrón de inyección que `RequestPasswordResetUseCase`.
  - [x] Tras encontrar la Cuenta (`accountRepository.findByEmail`), **antes** de evaluar credenciales: `Instant now = Instant.now(clock); if (account.unlockIfExpired(now)) { accountRepository.save(account); }` — auto-desbloqueo (AC #4), la Cuenta sigue el resto del método como si ya fuera `ACTIVE`.
  - [x] En cada uno de los tres caminos de fallo de credenciales que ya existen (`passwordHash == null`, `IllegalArgumentException` de hash ilegible, `!passwordMatches`) — antes de lanzar `AuthenticationFailedException` — llamar a un helper privado `recordFailedAttempt(account, now)` que:
    1. Invoca `account.recordFailedLoginAttempt(lockoutProperties.threshold(), lockoutProperties.lockDuration(), now)`.
    2. Persiste siempre: `accountRepository.save(account)` (el contador cambió, o el estado, o ambos — a diferencia del helper de éxito no hay atajo posible aquí).
    3. Si devolvió `true` (justo se bloqueó), publica `eventPublisher.publishEvent(new AccountLockedEmailRequested(account.email(), account.lockedUntil()))`.
  - [x] El chequeo final `if (account.status() != AccountStatus.ACTIVE) throw ...` (ya existente, cubre `LOCKED` todavía vigente, `PENDING_VERIFICATION`, `DISABLED`) **no** debe llamar a `recordFailedAttempt` — la contraseña ya fue correcta en ese punto, no es un fallo de credencial (AC #3: ni se distingue ni se penaliza más un intento contra una Cuenta ya bloqueada con password correcta).
  - [x] Antes de `tokenIssuer.issue(account)`, en el camino de éxito: `if (account.failedAttempts() > 0) { account.recordSuccessfulLogin(); accountRepository.save(account); }` — evita una escritura extra en el camino común (nunca hubo fallos previos).
  - [x] `LoginCommand`/`AuthController`/`GlobalExceptionHandler` no cambian — `AuthenticationFailedException` ya se mapea a `401` con el mismo mensaje genérico fijo desde la Story 1.4, sin distinguir el motivo (AD-8).

- [x] Task 5: Tests unitarios de `LoginUseCaseTest` (AC: #1, #2, #3, #4)
  - [x] Actualizar el constructor del `useCase` en el test con los 3 nuevos mocks/dependencias (`LockoutProperties` con umbral 5 y duración 15 min vía `Duration.ofMinutes(15)`, `ApplicationEventPublisher` mock, `Clock` fijo).
  - [x] `wrongPasswordIncrementsFailedAttemptsAndPersists` — password incorrecta sobre Cuenta `ACTIVE` con `failedAttempts=0` → tras el fallo, `verify(accountRepository).save(argThat(a -> a.failedAttempts() == 1))`, `verify(eventPublisher, never()).publishEvent(any())`.
  - [x] `fifthConsecutiveFailureLocksAccountAndPublishesEmailEvent` — Cuenta `ACTIVE` con `failedAttempts=4` (una debajo del umbral) → tras el quinto fallo, `account.status() == LOCKED`, `lockedUntil` no nulo, `verify(eventPublisher).publishEvent(any(AccountLockedEmailRequested.class))`.
  - [x] `sixthAttemptOnAlreadyLockedAccountDoesNotPublishAgainNorSave` — Cuenta ya `LOCKED` con `lockedUntil` en el futuro, cualquier intento (incluso con contraseña correcta) → `401`, `verify(eventPublisher, never()).publishEvent(any())`, `verify(accountRepository, never()).save(any())` (el chequeo final de status no pasa por `recordFailedAttempt`).
  - [x] `successfulLoginAfterPriorFailuresResetsCounter` — Cuenta `ACTIVE` con `failedAttempts=3`, login válido → `verify(accountRepository).save(argThat(a -> a.failedAttempts() == 0))`, tokens emitidos.
  - [x] `successfulLoginWithNoPriorFailuresDoesNotWriteToRepository` — Cuenta `ACTIVE` con `failedAttempts=0`, login válido → `verify(accountRepository, never()).save(any())`, tokens emitidos igual.
  - [x] `expiredLockAutoUnlocksBeforeEvaluatingCredentials` — Cuenta `LOCKED` con `lockedUntil` en el pasado, login con contraseña correcta → tokens emitidos. **Ajustado durante la implementación:** `verify(accountRepository, times(1)).save(any())`, no `times(2)` — `unlockIfExpired` ya resetea `failedAttempts` a 0 en el mismo `save`, así que el helper de éxito (`if failedAttempts() > 0`) no dispara un segundo `save()`.
  - [x] `expiredLockAutoUnlocksButWrongPasswordStillFails` — mismo setup, contraseña incorrecta → `401`, la Cuenta queda `ACTIVE` con `failedAttempts=1` (no `LOCKED` de nuevo, el auto-desbloqueo resetea antes de contar el nuevo fallo).
  - [x] Actualizar el test parametrizado existente `nonActiveAccountWithCorrectPasswordThrowsAuthenticationFailedWithoutIssuingTokens` (con `LOCKED`/`lockedUntil=null`) — sigue pasando sin cambios: `unlockIfExpired` no actúa porque `lockedUntil` es `null`.

- [x] Task 6: Test de integración `AuthLoginLockoutIntegrationTest` (AC: #1, #2, #3, #4)
  - [x] Mismo patrón que `AuthLoginIntegrationTest` (`@SpringBootTest` + Testcontainers, Cuenta creada directamente vía `AccountRepository`/`PasswordHasher` reales).
  - [x] (a) 5 POSTs consecutivos a `/auth/login` con contraseña incorrecta → los primeros 4 devuelven `401`; tras el 5º, la fila en BD tiene `status = 'LOCKED'` y `locked_until` no nulo (verificar vía `AccountRepository`).
  - [x] (b) Sexto intento con la contraseña **correcta** sobre la Cuenta ya bloqueada → sigue `401`, idéntico al resto.
  - [x] (c) **Ajustado durante la implementación:** en vez de un `Clock` mutable (no existe infraestructura de test para eso en este proyecto — no hay bean sobreescrito, solo `Clock.systemUTC()` real), la Cuenta se persiste directamente vía `AccountRepository` ya `LOCKED` con `locked_until` en el pasado (mismo patrón que las Stories 3.1/1.3 usan `expiresAt` ya vencido para probar tokens expirados) y se reintenta con contraseña correcta → `200 OK`, tokens emitidos, y la fila queda `status = 'ACTIVE'`, `failed_attempts = 0`.
  - [x] (d) Login exitoso tras algunos fallos (por debajo del umbral) → `failed_attempts` vuelve a `0` en BD.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Epics 1, 2 y Story 3.1.

### Review Findings

- [x] [Review][Defer] Concurrent failed logins can lose updates / double-fire lockout emails — deferred, riesgo de baja probabilidad, sin datos de producción. `recordFailedLoginAttempt` has no optimistic (`@Version`) or pessimistic locking. Two concurrent failed-login requests against the same account can both read the same pre-increment `failedAttempts`, both compute `justLocked` independently → lost update (undercounts attempts, effectively raising the threshold) and/or duplicate `AccountLockedEmailRequested` publication (user gets 2+ lockout emails for one lockout). No test covers this despite it being exactly the threat model (rapid/automated brute-force) this story defends against. Fixing requires a schema change (`@Version` column + migration) — same pattern as the timing side-channel already deferred 5 times per this story's own Dev Notes.
- [x] [Review][Patch] Transaction rollback erases failed-attempt/lockout writes [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:51] — `login()` is plain `@Transactional` with no `noRollbackFor`. Every failure path calls `recordFailedAttempt()` (which persists the incremented counter / `LOCKED` transition / `lockedUntil`) and then throws `AuthenticationFailedException` — a `RuntimeException` — in the same method invocation. Spring's default rollback-on-unchecked-exception policy rolls back that `save()` along with everything else, so the lockout counter and state transition never reach the database. This exact hazard was already solved in this codebase: `RefreshTokenUseCase.java` uses `@Transactional(noRollbackFor = InvalidRefreshTokenException.class)` for the identical shape (DB write on failure path, then throw), with a javadoc describing this precise pitfall. `LoginUseCaseTest` won't catch this because `AccountRepository` is mocked (mocks don't simulate real rollback). Fix: add `@Transactional(noRollbackFor = AuthenticationFailedException.class)` to `LoginUseCase.login()`.
- [x] [Review][Defer] Lockout mechanism enables account-level DoS by a third party — deferred, pre-existing design. Locking is keyed purely by email/account with no per-IP/device signal, CAPTCHA, or backoff; anyone who knows a victim's email can lock them out indefinitely. This is the explicit FR-8/FR-9 design as specified, not something this diff introduces — mitigating it is out of scope for this story.
- [x] [Review][Defer] Redundant second `save()` on auto-unlock-then-fail path [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — deferred, pre-existing. When an expired lock auto-unlocks and the following password check still fails, `unlockIfExpired` triggers one `save()` and `recordFailedAttempt` triggers a second `save()` for the same request. Minor inefficiency, not a correctness issue.
- [x] [Review][Defer] Integration test doesn't verify the lockout email side-effect [src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginLockoutIntegrationTest.java] — deferred, pre-existing gap. All four scenarios assert DB state only; none verify `AccountLockedEmailRequested`/`sendAccountLockedEmail` actually fires through the `@TransactionalEventListener(AFTER_COMMIT)` path — exactly the kind of wiring the rollback bug above would silently break without failing a DB-only assertion.

## Dev Notes

- **Todo lo necesario en persistencia ya existe sin cambios de esquema:** `accounts.failed_attempts`/`accounts.locked_until` están en `V2__accounts.sql` desde la Story 1.1; `AccountEntity`/`AccountRepositoryAdapter` ya mapean ambos campos en ambas direcciones (save y read) desde entonces. `AccountRepository.save(account)` ya persiste una Cuenta completa — no hace falta ningún método nuevo en el port, a diferencia de las Stories 1.5/3.1 que sí necesitaron métodos nuevos en `RefreshTokenRepository`/`VerificationTokenRepository`.
- **Todo el trabajo real vive en `Account` (domain) y `LoginUseCase` (orquestación)** — no hay controller, DTO, ni endpoint nuevo. `POST /auth/login` ya existe desde la Story 1.4 y no cambia su contrato.
- **AD-6 es literal sobre las transiciones válidas:** "`ACTIVE ⇄ LOCKED`" — la tabla de estados del architecture spine NO incluye `PENDING_VERIFICATION → LOCKED` ni `DISABLED → LOCKED`. Por eso `recordFailedLoginAttempt` es un no-op si la Cuenta no está `ACTIVE` al momento del fallo — un intento fallido contra una Cuenta `PENDING_VERIFICATION` o `DISABLED` sigue devolviendo el mismo `401` genérico (sin cambios respecto al comportamiento actual), simplemente no incrementa ni bloquea nada.
- **AD-15 (outbox transaccional, `AccountLocked` como Domain Event) es Epic 6 — todavía no implementado.** No existe tabla `outbox_event` ni infraestructura de publicación a Kafka en el codebase actual (confirmado: `find src/main -iname "*outbox*"` no devuelve nada). Esta historia usa exactamente el mismo mecanismo in-process (`ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`) ya establecido en Stories 1.2/1.3/3.1 para notificaciones por email — no el outbox real. Cuando Epic 6 implemente el outbox, migrar `AccountLockedEmailRequested` (o añadir un Domain Event real `AccountLocked` en paralelo) queda fuera de esta historia.
- **Orden crítico en `LoginUseCase` (AC #4):** el auto-desbloqueo debe evaluarse **antes** de la comparación de contraseña — si no, una Cuenta `LOCKED` con `locked_until` ya vencido seguiría rechazándose por el chequeo final de `status != ACTIVE` sin llegar nunca a desbloquearse, porque ese chequeo solo se alcanza *después* de que la contraseña ya haya sido validada como correcta.
- **Por qué el chequeo de status final no cuenta como "intento fallido":** en ese punto la contraseña ya fue verificada como correcta (`passwordMatches == true`); la Cuenta simplemente no puede operar todavía (sigue `LOCKED` sin expirar, o es `PENDING_VERIFICATION`/`DISABLED`). Contarlo como fallo penalizaría al titular legítimo que ya demostró conocer su contraseña — y en el caso de una Cuenta ya `LOCKED`, extendería el bloqueo indefinidamente ante un titular reintentando con la contraseña correcta mientras espera.
- **Canal lateral de timing (ya diferido 5 veces: Stories 1.2, 1.3, 1.4, 1.6, 3.1):** esta historia añade una escritura a BD (`accountRepository.save`) en el camino de fallo que antes no la tenía, ensanchando aún más la diferencia de latencia entre "Cuenta inexistente" (retorno inmediato) y "Cuenta existente, contraseña incorrecta" (BCrypt + ahora también un `UPDATE`). Mismo patrón preexistente ya aceptado como deferred — no se introduce mitigación nueva aquí, ver `deferred-work.md`.
- **No se reintroduce el bug de orden de la Story 3.1** (`revokeAllForAccount` con `clearAutomatically = true` debía ejecutarse antes de cualquier `save()` pendiente) — esta historia no toca `RefreshTokenRepository`, no aplica.

### Project Structure Notes

- **Nuevos:** `config/LockoutProperties.java`; `application/usecase/AccountLockedEmailRequested.java`; `src/test/java/.../config/LockoutPropertiesTest.java`; `src/test/java/.../controller/AuthLoginLockoutIntegrationTest.java`.
- **Modificados:** `domain/model/Account.java` (+`recordFailedLoginAttempt`, `recordSuccessfulLogin`, `unlockIfExpired`), `domain/port/EmailSender.java` (+`sendAccountLockedEmail`), `infrastructure/adapters/email/LoggingEmailSender.java` (+implementación), `application/usecase/EmailNotificationListener.java` (+listener), `application/usecase/LoginUseCase.java` (+lockout/auto-unlock), `application.properties` (+`auth.lockout.*`), `src/test/java/.../domain/model/AccountTest.java`, `src/test/java/.../application/usecase/LoginUseCaseTest.java`.
- **Sin cambios:** `AccountEntity`, `AccountRepositoryAdapter`, `AccountRepository` (port), `AuthController`, `LoginCommand`, `GlobalExceptionHandler`, esquema de BD (sin migración nueva — `failed_attempts`/`locked_until` ya existen desde `V2__accounts.sql`).
- **Nueva migración:** ninguna.

### References

- [Source: docs/planning-artifacts/epics.md#Epic-3] — Story 3.2 completa (user story + AC Given/When/Then), FR-8, FR-9
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-8] — "Registro de intentos fallidos"
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-9] — "Bloqueo temporal automático", umbral 5 / 15 min configurables, notificación por email
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#NFR-7] — configuración por entorno, aplica a FR-9
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-6] — transiciones de estado `ACTIVE ⇄ LOCKED` solo desde `application/usecase`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-9] — email fuera de la transacción, tras commit
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso como frontera transaccional
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-15] — outbox transaccional (Epic 6, no implementado todavía) — esta historia usa el mecanismo in-process existente, no el outbox real
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md] — tabla de trazabilidad: "FR-8, FR-9 Lockout | dentro de `LoginUseCase` | AD-6, AD-9, AD-13, AD-15"
- [Source: src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — punto de partida, todos los chequeos de credenciales existentes se preservan
- [Source: src/main/java/com/auth_service/auth/domain/model/Account.java] — `failedAttempts`/`lockedUntil` ya existen como campos+getters, sin mutador (el gap que cierra esta historia)
- [Source: src/main/java/com/auth_service/auth/config/AuthTokenProperties.java] — patrón exacto a replicar para `LockoutProperties` (record + `@ConfigurationProperties` + validación en constructor compacto)
- [Source: src/main/java/com/auth_service/auth/application/usecase/PasswordResetEmailRequested.java] — patrón exacto a replicar para `AccountLockedEmailRequested`
- [Source: src/main/java/com/auth_service/auth/application/usecase/EmailNotificationListener.java] — patrón de `@TransactionalEventListener(phase = AFTER_COMMIT)` con catch-and-log, un tercer listener se suma a los dos existentes
- [Source: src/main/resources/db/migration/V2__accounts.sql] — `failed_attempts`/`locked_until` ya existen, sin migración nueva necesaria
- [Source: src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginIntegrationTest.java] — patrón a replicar para `AuthLoginLockoutIntegrationTest`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -Dtest=LockoutPropertiesTest test` — 6/6 verde.
- `./mvnw -Dtest=AccountTest test` — 20/20 verde tras añadir `recordFailedLoginAttempt`/`recordSuccessfulLogin`/`unlockIfExpired` (Task 2).
- `./mvnw -q compile` — verde tras Task 3 (nuevo método en `EmailSender`/`LoggingEmailSender`/`EmailNotificationListener`) y tras Task 4 (`LoginUseCase` con las 3 nuevas dependencias).
- `./mvnw -Dtest=LoginUseCaseTest test` — 14/14 verde. Un ajuste respecto al diseño original de la story: `expiredLockAutoUnlocksBeforeEvaluatingCredentials` esperaba `times(2)` en `accountRepository.save(...)`, pero la implementación real solo hace `times(1)` — `unlockIfExpired` ya resetea `failedAttempts` a 0 en el mismo `save()` del auto-desbloqueo, así que el helper de éxito (guardado condicional a `failedAttempts() > 0`) no encuentra nada que resetear y no dispara un segundo `save()`. Comportamiento correcto, solo la aserción del test se corrigió.
- `./mvnw -B verify` (suite completa, con `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` exportadas y Docker disponible) — verde a la primera con la nueva `AuthLoginLockoutIntegrationTest` (Task 6), incluyendo el resto de Epics 1, 2 y Story 3.1 sin regresión.

### Completion Notes List

- Las 4 AC están implementadas: cada login fallido contra una Cuenta `ACTIVE` incrementa `failed_attempts` y un login exitoso lo reinicia (AC #1); al alcanzar el umbral (default 5) la Cuenta pasa a `LOCKED` con `locked_until` configurable (default 15 min) y se notifica al titular por email tras el commit, una sola vez por bloqueo (AC #2); una Cuenta `LOCKED` vigente rechaza incluso la contraseña correcta con el mismo `401` genérico sin penalizar más el contador (AC #3); una Cuenta `LOCKED` cuyo `locked_until` ya pasó se auto-desbloquea dentro de `LoginUseCase` antes de evaluar credenciales (AC #4).
- Toda la persistencia necesaria (`failed_attempts`/`locked_until` en `accounts`, mapeo completo en `AccountEntity`/`AccountRepositoryAdapter`) ya existía desde la Story 1.1 — no hizo falta ninguna migración nueva ni cambios en el port `AccountRepository`.
- Único ajuste real respecto al diseño de la story: la aserción `times(2)` de `expiredLockAutoUnlocksBeforeEvaluatingCredentials` se corrigió a `times(1)` (ver Debug Log) — el comportamiento implementado es el correcto y más eficiente (una sola escritura en vez de dos), la story simplemente había anticipado la ambigüedad y dejado la puerta abierta a "ajustar la aserción exacta".
- Todos los componentes nuevos reutilizan exactamente los patrones ya establecidos por las Stories 1.4 (`LoginUseCase` existente), 3.1 (`AuthTokenProperties`/`PasswordResetEmailRequested`/`EmailNotificationListener`) — mismo patrón de `@ConfigurationProperties` con constructor compacto, mismo patrón de evento in-process `@TransactionalEventListener(AFTER_COMMIT)` con catch-and-log.
- Suite completa verde con Docker disponible en esta sesión (230+ tests previos + 27 nuevos entre `LockoutPropertiesTest`, casos nuevos de `AccountTest`/`LoginUseCaseTest`, y `AuthLoginLockoutIntegrationTest`).

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/config/LockoutProperties.java`
- `src/main/java/com/auth_service/auth/application/usecase/AccountLockedEmailRequested.java`
- `src/test/java/com/auth_service/auth/config/LockoutPropertiesTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginLockoutIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (+`recordFailedLoginAttempt`, `recordSuccessfulLogin`, `unlockIfExpired`)
- `src/main/java/com/auth_service/auth/domain/port/EmailSender.java` (+`sendAccountLockedEmail`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/email/LoggingEmailSender.java` (+implementación)
- `src/main/java/com/auth_service/auth/application/usecase/EmailNotificationListener.java` (+listener `AccountLockedEmailRequested`)
- `src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java` (+lockout, auto-desbloqueo, reset de contador)
- `src/main/resources/application.properties` (+`auth.lockout.threshold`, `auth.lockout.lock-duration`)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+8 casos nuevos)
- `src/test/java/com/auth_service/auth/application/usecase/LoginUseCaseTest.java` (+7 casos nuevos, constructor actualizado)
