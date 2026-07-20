---
baseline_commit: b3c29b8a097e5d016eca43db29226a4285584c14
---

# Story 3.1: Recuperación de contraseña

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a titular que olvidó su contraseña,
I want restablecerla mediante un enlace temporal a mi email,
so that recupere el acceso sin intervención de nadie. (FR-7)

## Acceptance Criteria

1. Dado un POST `/auth/forgot-password` con cualquier email (exista o no la Cuenta), `RequestPasswordResetUseCase` responde con el mismo `202 ACCEPTED` y el mismo cuerpo genérico en ambos casos (anti-enumeración, NFR-2) — la respuesta nunca revela si el email está registrado.
2. Dado un email que **sí** corresponde a una Cuenta existente, la solicitud invalida cualquier Token `PASSWORD_RESET` activo previo de esa Cuenta y emite uno nuevo (hash SHA-256 en BD, TTL 1 h configurable, un solo uso), enviado por email tras el commit de la transacción (AD-5, AD-9) — nunca dentro de la transacción de negocio.
3. Dado un token de recuperación vigente y no consumido, y una contraseña nueva que cumple la política (≥8, mayúscula, minúscula, dígito — misma regla de FR-1), al hacer POST `/auth/reset-password` `ResetPasswordUseCase` actualiza la contraseña de la Cuenta (`HashedPassword`, AD-14), el token queda consumido y **todas** las Familias de Refresh Tokens de la Cuenta quedan revocadas (cierra sesión en todos los dispositivos, AD-4) — la respuesta es `200 OK`.
4. Dado un token expirado, ya consumido, o inexistente, al hacer POST `/auth/reset-password` la operación se rechaza con `400` `application/problem+json` (mismo mecanismo que `/auth/verify`, AD-8) y la contraseña anterior sigue vigente — ninguna Familia de Tokens se revoca.
5. Dado una contraseña nueva que incumple la política de validación, al hacer POST `/auth/reset-password` con un token por lo demás válido, la operación se rechaza con `400` y el token **no** se consume (puede reintentarse con una contraseña válida antes de expirar).

## Tasks / Subtasks

- [x] Task 1: `AuthTokenProperties` — TTL configurable para `PASSWORD_RESET` (AC: #2)
  - [x] Añadir componente `passwordResetTtl` al record `AuthTokenProperties` (`src/main/java/com/auth_service/auth/config/AuthTokenProperties.java`), mismo patrón que `verificationTtl`: default `Duration.ofHours(1)` en el constructor compacto si la propiedad no está definida.
  - [x] Nueva propiedad `auth.token.password-reset-ttl=1h` en `application.properties`, junto a `auth.token.verification-ttl` existente.
  - [x] Actualizar `AuthTokenPropertiesTest` con el caso del nuevo default/override, mismo patrón que el test existente para `verificationTtl`.

- [x] Task 2: `RefreshTokenRepository` — nuevo método `revokeAllForAccount` (AC: #3)
  - [x] **Gap confirmado:** el repositorio actual solo revoca por `familyId` (`revokeFamily`, usado por `LogoutUseCase` y `RefreshTokenUseCase.revokeFamilyOnReuse`) — no existe un método que revoque **todas** las familias de una Cuenta, que es lo que exige el AC #3 de esta historia.
  - [x] Añadir a `domain/port/RefreshTokenRepository.java`: `int revokeAllForAccount(AccountId accountId, Instant revokedAt);`
  - [x] Añadir a `infrastructure/adapters/postgresql/RefreshTokenJpaRepository.java`, mismo patrón que `revokeFamily`:
    ```java
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :revokedAt WHERE r.accountId = :accountId AND r.revokedAt IS NULL")
    int revokeAllForAccount(@Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);
    ```
  - [x] Delegar en `RefreshTokenRepositoryAdapter.java`, mismo patrón que la delegación existente de `revokeFamily`.
  - [x] No hace falta migración: `idx_refresh_tokens_account_id` ya existe (`V3__refresh_tokens.sql`), la query queda indexada.
  - [x] Test nuevo en `RefreshTokenRepositoryAdapterTest.java`: revocar todas las familias de una Cuenta con ≥2 familias activas y confirmar que ambas quedan `revokedAt` no-nulo, y que una familia de **otra** Cuenta no se toca.

- [x] Task 3: `Account` — mutador `changePassword` (AC: #3)
  - [x] **Gap confirmado:** `Account.passwordHash` es hoy `private final HashedPassword passwordHash;` (sin mutador) — la única mutación existente en `Account` es `activate()` sobre el campo `status` (que ya es no-final, mismo patrón a replicar).
  - [x] Cambiar `passwordHash` de `final` a mutable, mismo estilo que `status`.
  - [x] Añadir `public void changePassword(HashedPassword newPasswordHash) { this.passwordHash = newPasswordHash; }` — sin validación de estado adicional (ninguna AC de esta historia restringe por `AccountStatus`; una Cuenta `LOCKED` puede restablecer su contraseña, lo cual además es coherente con el flujo real de recuperación).

- [x] Task 4: `EmailSender` — nuevo método + adapter dev (AC: #2)
  - [x] Añadir a `domain/port/EmailSender.java`: `void sendPasswordResetEmail(Email recipient, String rawToken);`
  - [x] Implementar en `infrastructure/adapters/email/LoggingEmailSender.java`, mismo patrón que `sendVerificationEmail` (loggea `appBaseUrl + "/auth/reset-password?token=" + rawToken`).
  - [x] Nuevo record `PasswordResetEmailRequested(Email recipient, String rawToken)` en `application/usecase/`, mismo paquete y mismo Javadoc-caveat ("no es un Domain Event") que `VerificationEmailRequested`.
  - [x] Nuevo método en `EmailNotificationListener.java`: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` sobre `PasswordResetEmailRequested`, llamando a `emailSender.sendPasswordResetEmail(...)` dentro de un `try/catch(RuntimeException)` que solo loggea (mismo patrón exacto que `onVerificationEmailRequested` — un fallo de envío nunca revierte ni re-lanza, AD-9).

- [x] Task 5: `RequestPasswordResetUseCase` (AC: #1, #2)
  - [x] Nueva clase en `application/usecase/`, `@Transactional`, recibe `AccountRepository`, `VerificationTokenRepository`, `ApplicationEventPublisher`, `AuthTokenProperties`, `Clock` — mismo patrón de dependencias que `ResendVerificationUseCase`.
  - [x] `RequestPasswordResetCommand(String email)` como record de entrada.
  - [x] Buscar la Cuenta por `Email` (`accountRepository.findByEmail(...)`) — si no existe, **no hacer nada** (ni token ni evento) y retornar sin error (el controller no distingue el resultado, AC #1).
  - [x] Si la Cuenta existe: `verificationTokenRepository.invalidateActiveTokens(account.id(), VerificationPurpose.PASSWORD_RESET, Instant.now(clock))`, luego `VerificationToken.issue(account.id(), VerificationPurpose.PASSWORD_RESET, authTokenProperties.passwordResetTtl(), clock)`, `verificationTokenRepository.save(issued.token())`, y publicar `PasswordResetEmailRequested(account.email(), issued.rawToken())` — exactamente el mismo patrón de `ResendVerificationUseCase.resend` (Task 2 del catálogo de referencia), sustituyendo `EMAIL_VERIFICATION` por `PASSWORD_RESET`.
  - [x] **No** restringir por `AccountStatus` — a diferencia del login, una Cuenta `PENDING_VERIFICATION` o `LOCKED` también puede solicitar recuperación (ninguna AC lo prohíbe y es el comportamiento estándar de la industria); solo una Cuenta puramente federada sin contraseña local podría considerarse, pero ninguna AC de esta historia distingue ese caso — no añadir esa restricción a menos que un AC la exija.
  - [x] Test unitario (`RequestPasswordResetUseCaseTest`, mismo patrón que `ResendVerificationUseCaseTest`): (a) Cuenta existente → invalida tokens previos, guarda uno nuevo, publica el evento con el email correcto; (b) Cuenta inexistente → no se toca `VerificationTokenRepository` ni se publica evento, pero el caso de uso no lanza excepción.

- [x] Task 6: `ResetPasswordUseCase` (AC: #3, #4, #5)
  - [x] Nueva clase en `application/usecase/`, `@Transactional`, recibe `AccountRepository`, `VerificationTokenRepository`, `RefreshTokenRepository`, `PasswordHasher`, `Clock`.
  - [x] `ResetPasswordCommand(String rawToken, String newRawPassword)` como record de entrada.
  - [x] Orden exacto a replicar de `VerifyAccountUseCase.verify` (Task 2 del catálogo): `VerificationToken.hashRawToken(rawToken)` → `findByTokenHash(...).orElseThrow(DomainValidationException)` → verificar `token.purpose() == VerificationPurpose.PASSWORD_RESET` (si no, mismo `DomainValidationException` genérico — nunca revelar que el token existe pero es de otro propósito) → **antes de tocar la Cuenta**, construir `RawPassword`/`HashedPassword` a partir de `newRawPassword` (así una contraseña inválida falla con AC #5 **sin** consumir el token) → `token.consume(clock)` (lanza si ya consumido/expirado, AC #4) → `verificationTokenRepository.save(consumedToken)` → cargar la `Account` por `token.accountId()` → `account.changePassword(hashedPassword)` (Task 3) → `accountRepository.save(account)` → `refreshTokenRepository.revokeAllForAccount(account.id(), Instant.now(clock))` (Task 2).
  - [x] **Orden crítico:** validar la contraseña nueva (construir `HashedPassword`) **antes** de `token.consume(clock)`, para que AC #5 (contraseña inválida) nunca consuma el token — a diferencia de `VerifyAccountUseCase` donde no hay un input adicional que pueda fallar tras el consumo.
  - [x] Test unitario (`ResetPasswordUseCaseTest`, mismo patrón que `VerifyAccountUseCaseTest`): (a) token válido + password válida → password actualizada, token consumido, `revokeAllForAccount` invocado con el `accountId` correcto; (b) token inexistente/expirado/consumido → `DomainValidationException`, ni `Account` ni `RefreshTokenRepository` tocados; (c) token de propósito `EMAIL_VERIFICATION` (no `PASSWORD_RESET`) → mismo `DomainValidationException` genérico, no se filtra que el token existe con otro propósito; (d) token válido + password que incumple la política → `DomainValidationException` de `HashedPassword`/`RawPassword`, el token permanece sin consumir (verificar con una segunda llamada exitosa usando el mismo token).

- [x] Task 7: `AuthController` — endpoints `/auth/forgot-password` y `/auth/reset-password` (AC: #1, #3, #4, #5)
  - [x] `ForgotPasswordRequest(@NotBlank @Size(max=254) String email)` en `infrastructure/controller/dto/`, mismo patrón que `ResendVerificationRequest`.
  - [x] `POST /auth/forgot-password`: mismo patrón exacto que `POST /auth/resend-verification` (`AuthController.java`) — constante estática `MessageResponse` con mensaje genérico, `202 ACCEPTED` siempre, el controller ignora cualquier resultado interno del caso de uso (no hay `ResendVerificationResult`-like enum que distinguir aquí porque AC #1 exige indistinguibilidad total).
  - [x] `ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword)` en `infrastructure/controller/dto/`.
  - [x] `POST /auth/reset-password`: mismo patrón que `POST /auth/verify` — sin try/catch, deja que `DomainValidationException` propague a `GlobalExceptionHandler` (ya maneja ese tipo, `400` `application/problem+json`, sin cambios necesarios ahí); éxito → `200 OK` con `MessageResponse` fija.
  - [x] Ambos endpoints ya quedan públicos: `/auth/**` está en `PUBLIC_ENDPOINTS` de `SecurityConfig` desde la Story 1.1, agnóstico de la ruta exacta — no requiere cambios en `SecurityConfig`.

- [x] Task 8: Tests de integración (AC: #1, #2, #3, #4, #5)
  - [x] `AuthForgotPasswordIntegrationTest` (mismo patrón que `AuthRegisterIntegrationTest`/`AuthVerificationIntegrationTest`, `@SpringBootTest` + Testcontainers): (a) email de Cuenta existente → `202`; (b) email inexistente → mismo `202`; (c) segunda solicitud sobre la misma Cuenta → el token anterior queda invalidado (`consumed_at` seteado por `invalidateActiveTokens`), reutilizarlo en `/auth/reset-password` falla con `400`.
  - [x] `AuthResetPasswordIntegrationTest`: (a) token vigente + password válida → `200`, login posterior con la password vieja falla, login con la nueva funciona, y un Refresh Token emitido **antes** del reset ya no puede canjearse (`401` en `/auth/refresh`); (b) token expirado/inexistente → `400` `application/problem+json`; (c) reutilizar el mismo token dos veces → la segunda vez `400` (ya consumido); (d) token válido + password que incumple la política → `400`, y el mismo token todavía sirve para un reintento válido inmediatamente después.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Epics 1 y 2.
  - [x] **Bug real encontrado y corregido durante esta task (no en el diseño original de Task 6):** `refreshTokenRepository.revokeAllForAccount(...)` usa `@Modifying(clearAutomatically = true)`, que ejecuta `entityManager.clear()`. Llamarlo **después** de `verificationTokenRepository.save(consumedToken)` y `accountRepository.save(account)` (como decía el diseño original de Task 6) descarta esos cambios *aún no flusheados* (`merge()` no ejecuta `UPDATE` de inmediato) — el caso de uso "terminaba" con `200 OK` pero ni el token quedaba consumido ni la contraseña se actualizaba en BD. Detectado por los tests de integración de esta misma task (reutilizar el token dos veces devolvía `200` en vez de `400`; login con la password vieja seguía funcionando tras el reset). **Fix:** `revokeAllForAccount` se mueve a ser la primera escritura en BD de la transacción — antes de cualquier `save()` — usando `token.accountId()` (ya disponible tras `token.consume()`, sin necesidad de cargar la `Account` primero). [ResetPasswordUseCase.java]

### Review Findings

- [x] [Review][Patch] Condición de carrera: dos solicitudes concurrentes a `/auth/reset-password` con el mismo token pueden superar ambas la comprobación de "no consumido" antes de que cualquiera confirme — `ResetPasswordUseCase.resetPassword` lee el token vía `findByTokenHash`, luego llama a `token.consume(clock)` en memoria antes de persistir. **Aplicado:** nuevo `VerificationTokenRepository.consumeIfActive(tokenHash, now)` con `UPDATE ... WHERE consumed_at IS NULL AND expires_at > :now` verificando filas afectadas (mismo patrón que `revokeFamily`/`revokeAllForAccount`); si afecta 0 filas, `ResetPasswordUseCase` lanza `DomainValidationException` sin tocar la Cuenta ni las Familias. Test nuevo `concurrentConsumeLosesRaceThrowsAndTouchesNothingElse` [src/main/java/com/auth_service/auth/application/usecase/ResetPasswordUseCase.java, src/main/java/com/auth_service/auth/domain/port/VerificationTokenRepository.java, src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/VerificationTokenJpaRepository.java, src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/VerificationTokenRepositoryAdapter.java]

- [x] [Review][Patch] `AuthTokenProperties.passwordResetTtl` no tiene validación (null/cero/negativo/máximo), a diferencia de `refreshTtl` que rechaza cero, negativo y >90 días. **Aplicado:** mismo patrón exacto que `refreshTtl` (rechaza cero, negativo y >90 días); 4 tests nuevos en `AuthTokenPropertiesTest` [src/main/java/com/auth_service/auth/config/AuthTokenProperties.java:26]
- [x] [Review][Patch] `ResetPasswordRequest.newPassword` no tiene tope `@Size`, permitiendo una entrada no acotada hacia `passwordHasher.hash()` (bcrypt). **Aplicado:** `@Size(max = 72)`, mismo patrón que `RegisterRequest.password` [src/main/java/com/auth_service/auth/infrastructure/controller/dto/ResetPasswordRequest.java:7]
- [x] [Review][Patch] `ResetPasswordRequest.token` no tiene tope `@Size` antes de hashearse vía `VerificationToken.hashRawToken`. **Aplicado:** `@Size(max = 512)` [src/main/java/com/auth_service/auth/infrastructure/controller/dto/ResetPasswordRequest.java:6]
- [x] [Review][Patch] `ResetPasswordUseCaseTest.alreadyConsumedTokenThrowsAndTouchesNothing` no verifica `verify(verificationTokenRepository, never()).save(any())`, presente en el test hermano `wrongPurposeTokenThrowsAndTouchesNothing` — **obsoleto/resuelto de otra forma:** el fix de la condición de carrera de arriba elimina por completo la llamada a `.save()` de `ResetPasswordUseCase` (reemplazada por `.consumeIfActive()`); los 4 tests de camino de error ahora verifican `verify(verificationTokenRepository, never()).consumeIfActive(any(), any())`, incluido este [src/test/java/com/auth_service/auth/application/usecase/ResetPasswordUseCaseTest.java]
- [x] [Review][Patch] `ResetPasswordUseCase.resetPassword` lanza `IllegalStateException` con el `accountId` interno en el mensaje cuando la Cuenta del token no existe — sin handler dedicado en `GlobalExceptionHandler`, cae al genérico y expone el UUID interno en un 500. **Aplicado:** mensaje genérico sin el UUID [src/main/java/com/auth_service/auth/application/usecase/ResetPasswordUseCase.java:214]
- [x] [Review][Patch] `AuthForgotPasswordIntegrationTest.existingAccountReturns202AndPersistsANewPasswordResetToken` no verifica que el token se haya persistido pese a lo que promete su nombre — solo comprueba el 202. **Aplicado:** aserción vía `VerificationTokenJpaRepository` (accountId, purpose `PASSWORD_RESET`, `consumedAt` nulo) [src/test/java/com/auth_service/auth/infrastructure/controller/AuthForgotPasswordIntegrationTest.java:69]

- [x] [Review][Defer] Canal lateral de timing en anti-enumeración: el camino de Cuenta existente en `RequestPasswordResetUseCase` hace más trabajo (invalidar+emitir+publicar) que el de Cuenta inexistente (retorno inmediato), creando una diferencia de latencia medible pese al cuerpo de respuesta idéntico — deferred, patrón preexistente compartido con `/auth/resend-verification`.
- [x] [Review][Defer] Sin rate limiting en `/auth/forgot-password`, permite bombardear de emails la bandeja de una víctima arbitraria — deferred, preocupación transversal compartida por todos los endpoints públicos no autenticados (login, register, resend-verification), no específica de esta historia.
- [x] [Review][Defer] Un email con formato inválido en `/auth/forgot-password` lanza `DomainValidationException` desde el VO `Email` antes de llegar al camino genérico 202, produciendo una respuesta 400 distinguible — deferred, patrón preexistente idéntico en `ResendVerificationUseCase`.
- [x] [Review][Defer] `passwordHasher.hash()` (bcrypt) se ejecuta antes de `token.consume(clock)` en `ResetPasswordUseCase`, por lo que un token expirado/reutilizado igual paga el costo completo del hashing — deferred, el orden lo exige la propia AC #5 (la contraseña debe validarse antes de consumir el token) y la búsqueda por hash ya acota la superficie alcanzable a tokens válidos adivinados.
- [x] [Review][Defer] `Account.passwordHash` pasó de `final` a mutable sin un registro de auditoría `passwordChangedAt` — deferred, ninguna AC exige metadata de auditoría de seguridad/sesión.
- [x] [Review][Defer] El fix de orden `revokeAllForAccount`-antes-de-`save()` solo se ejercita en los tests de integración con Testcontainers (`disabledWithoutDocker = true`) — deferred, consistente con el patrón ya existente en toda la suite de tests de historias previas.
- [x] [Review][Defer] Solicitudes concurrentes a `/auth/forgot-password` para la misma Cuenta pueden superar `invalidateActiveTokens` + `issue` en carrera, dejando más de un token válido simultáneamente — deferred, severidad baja (impacto solo sobre el propio solicitante, sin acceso no autorizado).
- [x] [Review][Defer] `ResetPasswordUseCase` no verifica que la nueva contraseña sea distinta de la actual — un "reset" a la misma contraseña se acepta y sigue revocando todas las Familias de Refresh Tokens — deferred, ninguna AC exige esta comprobación.
- [x] [Review][Defer] Si `LoggingEmailSender.sendPasswordResetEmail` falla tras el commit, el error se loggea y se descarta sin reintento ni alerta — deferred, comportamiento esperado por AD-9 (el email nunca revierte la transacción de negocio), mismo patrón que el resto de notificaciones por email de la app.

## Dev Notes

- **Toda la infraestructura de tokens de un solo uso ya existe y se reutiliza sin cambios de esquema:** `VerificationToken` (domain), `VerificationTokenRepository` (port + adapter JPA), tabla `verification_tokens`. El enum `VerificationPurpose` **ya incluye `PASSWORD_RESET`** desde que se creó (comentario en el código: "lo emite la Story 3.1") — no hace falta tocar el enum ni la migración `V2__accounts.sql`.
- **Patrón a replicar exactamente, no reinventar:** issuing sigue el patrón de `ResendVerificationUseCase.resend` (invalidar tokens activos del mismo `purpose` → `VerificationToken.issue(...)` → `save` → publicar evento); consuming sigue el patrón de `VerifyAccountUseCase.verify` (hash → `findByTokenHash` → validar `purpose` → `consume(clock)` → `save` del token consumido → **solo entonces** mutar la Cuenta). Generación del token aleatorio y su hash SHA-256 (AD-5) viven enteramente dentro de `VerificationToken` — ningún caso de uso toca `SecureRandom`/`MessageDigest` directamente.
- **Dos gaps reales identificados en el código existente que esta historia debe cerrar** (no están simplemente "reutilizados", hay que añadirlos): (1) `RefreshTokenRepository` no tiene un método que revoque todas las familias de una Cuenta, solo por `familyId` individual (Task 2); (2) `Account` no expone ningún mutador de `passwordHash` (Task 3). Ambos deben seguir el estilo exacto de los mecanismos análogos ya existentes (`revokeFamily`, `activate()`), sin introducir abstracciones nuevas.
- **Orden crítico en `ResetPasswordUseCase` (AC #5):** la contraseña nueva debe validarse (construir `HashedPassword` vía `PasswordHasher`) **antes** de `token.consume(clock)`, para que una contraseña inválida no queme el token — el visitante puede reintentar con una contraseña válida usando el mismo enlace hasta que expire. `VerifyAccountUseCase` no tiene este problema porque no recibe ningún input adicional que pueda fallar.
- **Anti-enumeración (AC #1, NFR-2):** `RequestPasswordResetUseCase` **nunca** lanza ni retorna una señal distinguible entre "Cuenta no existe" y "Cuenta existe, token emitido" — el controller siempre responde `202` con el mismo `MessageResponse`, mismo patrón que `/auth/register` y `/auth/resend-verification`. En cambio `/auth/reset-password` (AC #4) **sí** distingue éxito de error — igual que `/auth/verify` — porque en ese punto el visitante ya posee el token del email, no hay enumeración posible.
- **Revocación de sesiones (AC #3, AD-4):** un password reset exitoso revoca **todas** las Familias de Refresh Tokens de la Cuenta (no solo la que originó el request, que ni siquiera existe en este flujo — el visitante llega sin sesión activa). Esto es distinto del logout (Story 1.6), que revoca solo la familia del token presentado.
- **No se restringe por `AccountStatus`** en ninguno de los dos casos de uso — ni el AC #1/#2 ni el #3/#4/#5 lo piden. Una Cuenta `LOCKED` puede recuperar su contraseña (comportamiento esperado: es precisamente el mecanismo de auto-servicio para recuperar acceso), y el reset no la desbloquea automáticamente (eso solo ocurre en `LoginUseCase` al expirar `locked_until`, Story 3.2 — fuera del alcance de esta historia).

### Project Structure Notes

- **Nuevos:** `application/usecase/RequestPasswordResetUseCase.java`, `RequestPasswordResetCommand.java`, `ResetPasswordUseCase.java`, `ResetPasswordCommand.java`, `PasswordResetEmailRequested.java`; `infrastructure/controller/dto/ForgotPasswordRequest.java`, `ResetPasswordRequest.java`.
- **Modificados:** `config/AuthTokenProperties.java` (+`passwordResetTtl`), `application.properties` (+`auth.token.password-reset-ttl`), `domain/port/RefreshTokenRepository.java` (+`revokeAllForAccount`), `infrastructure/adapters/postgresql/RefreshTokenJpaRepository.java` (+query), `infrastructure/adapters/postgresql/RefreshTokenRepositoryAdapter.java` (+delegación), `domain/model/Account.java` (`passwordHash` no-final +`changePassword`), `domain/port/EmailSender.java` (+`sendPasswordResetEmail`), `infrastructure/adapters/email/LoggingEmailSender.java` (+implementación), `application/usecase/EmailNotificationListener.java` (+listener), `infrastructure/controller/AuthController.java` (+2 endpoints).
- **Sin cambios:** `VerificationToken`, `VerificationPurpose` (ya tiene `PASSWORD_RESET`), `VerificationTokenRepository` + adapter, esquema de BD (`V2__accounts.sql` ya soporta cualquier `purpose` como texto libre), `SecurityConfig` (`/auth/**` ya público), `GlobalExceptionHandler` (ya maneja `DomainValidationException` → 400).
- **Nueva migración:** ninguna.

### References

- [Source: docs/planning-artifacts/epics.md#Epic-3] — Story 3.1 completa (user story + AC Given/When/Then), FR-7
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-7] — "Solicitud y restablecimiento", anti-enumeración, revocación de todas las Familias de Tokens
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-4] — Refresh Token opaco, rotación, revocación por familia
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-5] — tokens de un solo uso nunca en claro, hash SHA-256, TTL 1h para recuperación
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-8] — RFC 7807 + anti-enumeración
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-9] — email fuera de la transacción, tras commit
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-14] — Value Objects (`HashedPassword`)
- [Source: src/main/java/com/auth_service/auth/domain/model/VerificationPurpose.java] — `PASSWORD_RESET` ya declarado, sin usar hasta esta historia
- [Source: src/main/java/com/auth_service/auth/domain/model/VerificationToken.java] — `issue`, `consume`, `hashRawToken`, `generateRawToken` — mecanismo completo a reutilizar
- [Source: src/main/java/com/auth_service/auth/application/usecase/ResendVerificationUseCase.java] — patrón exacto de emisión (invalidar + issue + publish) a replicar
- [Source: src/main/java/com/auth_service/auth/application/usecase/VerifyAccountUseCase.java] — patrón exacto de consumo (hash + find + consume + save + mutar Account) a replicar
- [Source: src/main/java/com/auth_service/auth/application/usecase/EmailNotificationListener.java] — patrón de `@TransactionalEventListener(phase = AFTER_COMMIT)` con catch-and-log
- [Source: src/main/java/com/auth_service/auth/domain/port/RefreshTokenRepository.java] — gap: falta `revokeAllForAccount`, solo existe `revokeFamily`
- [Source: src/main/java/com/auth_service/auth/domain/model/Account.java] — gap: `passwordHash` es `final`, sin mutador; `status` (no-final) + `activate()` es el precedente de estilo a seguir
- [Source: src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java] — patrón `/auth/resend-verification` (anti-enumeración, 202) y `/auth/verify` (error distinguible, 400) a replicar respectivamente
- [Source: src/main/java/com/auth_service/auth/config/AuthTokenProperties.java] — patrón de TTL configurable con default en constructor compacto
- [Source: src/main/resources/db/migration/V3__refresh_tokens.sql] — `idx_refresh_tokens_account_id` ya existe, sin migración nueva necesaria

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -Dtest=AuthTokenPropertiesTest,ResendVerificationUseCaseTest,RegisterAccountUseCaseTest,TokenIssuerTest test` — 17/17 verde tras añadir `passwordResetTtl` (Task 1).
- `./mvnw -Dtest=RefreshTokenRepositoryAdapterTest test` — falló primero por un bug en el fixture del test nuevo (`persistAccount()` reutilizaba el mismo email hardcodeado para las dos Cuentas del test `revokeAllForAccount...`, violando el UNIQUE de `accounts.email`); corregido con una sobrecarga `persistAccount(String email)`. 6/6 verde después.
- `./mvnw -Dtest=AccountTest test` — 8/8 verde tras añadir `changePassword` (Task 3).
- `./mvnw -Dtest=RequestPasswordResetUseCaseTest test` — falló primero por ambigüedad de sobrecarga de Mockito (`verify(eventPublisher).publishEvent(any())` sin type witness); corregido con `any(PasswordResetEmailRequested.class)`. 3/3 verde después.
- `./mvnw -Dtest=ResetPasswordUseCaseTest test` — 5/5 verde a la primera.
- `./mvnw -Dtest=AuthControllerTest,UserControllerTest,SecurityConfigTest test` — 23/23 verde tras añadir los `@MockitoBean` de los dos casos de uso nuevos en `AuthControllerTest` (Task 7).
- `./mvnw -Dtest=AuthForgotPasswordIntegrationTest,AuthResetPasswordIntegrationTest test` — **falló primero con 2/7 tests en rojo** (login con password vieja seguía funcionando tras el reset; reutilizar el mismo token de recuperación devolvía `200` en vez de `400`). Causa raíz: `refreshTokenRepository.revokeAllForAccount(...)` (`@Modifying(clearAutomatically = true)`) se llamaba **después** de `verificationTokenRepository.save(consumedToken)` y `accountRepository.save(account)` — `entityManager.clear()` descartaba esos `merge()` aún no flusheados a BD. Corregido reordenando `ResetPasswordUseCase` para que `revokeAllForAccount` sea la primera escritura de la transacción. 12/12 verde después (ver Change Log).
- `./mvnw -B verify` (suite completa, con `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` exportadas y Docker disponible) — ver resultado final abajo.

### Completion Notes List

- Las 5 AC están implementadas: respuesta idéntica exista o no la Cuenta en `/auth/forgot-password` (AC #1); token `PASSWORD_RESET` con TTL configurable, invalidación de tokens previos y envío diferido tras commit (AC #2); reset exitoso actualiza la contraseña, consume el token y revoca todas las Familias de Refresh Tokens de la Cuenta (AC #3); token expirado/consumido/inexistente rechazado con `400` sin tocar nada (AC #4); contraseña que incumple la política rechazada sin consumir el token, permitiendo reintento (AC #5).
- Dos gaps reales del código existente, anticipados por la historia, cerrados sin abstracciones nuevas: `RefreshTokenRepository.revokeAllForAccount` (mismo patrón que `revokeFamily`) y `Account.changePassword` (mismo patrón que `activate()`).
- **Bug de concurrencia/persistencia real encontrado y corregido** (no anticipado por el diseño original de Task 6): ver el hallazgo detallado en Task 8 y el Change Log — `revokeAllForAccount` debe ejecutarse antes de cualquier `save()` pendiente en la misma transacción, nunca después, por su `clearAutomatically = true`.
- Todos los nuevos componentes reutilizan exactamente los patrones ya establecidos por las Stories 1.2/1.3 (`ResendVerificationUseCase`/`VerifyAccountUseCase`) — mismo mecanismo de `VerificationToken`/`VerificationPurpose.PASSWORD_RESET` (ya declarado desde antes de esta historia), mismo patrón de evento `@TransactionalEventListener(AFTER_COMMIT)`, mismo patrón de DTOs y respuestas anti-enumeración vs. distinguibles.
- Suite completa verde con Docker disponible en esta sesión (a diferencia de la Story 2.2, no hubo que saltar ningún test de Testcontainers).

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/application/usecase/RequestPasswordResetCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/RequestPasswordResetUseCase.java`
- `src/main/java/com/auth_service/auth/application/usecase/ResetPasswordCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/ResetPasswordUseCase.java`
- `src/main/java/com/auth_service/auth/application/usecase/PasswordResetEmailRequested.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/ForgotPasswordRequest.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/ResetPasswordRequest.java`
- `src/test/java/com/auth_service/auth/application/usecase/RequestPasswordResetUseCaseTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/ResetPasswordUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthForgotPasswordIntegrationTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthResetPasswordIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/config/AuthTokenProperties.java` (+`passwordResetTtl`)
- `src/main/resources/application.properties` (+`auth.token.password-reset-ttl`)
- `src/main/java/com/auth_service/auth/domain/port/RefreshTokenRepository.java` (+`revokeAllForAccount`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenJpaRepository.java` (+query `revokeAllForAccount`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenRepositoryAdapter.java` (+delegación)
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (`passwordHash` no-final, +`changePassword`)
- `src/main/java/com/auth_service/auth/domain/port/EmailSender.java` (+`sendPasswordResetEmail`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/email/LoggingEmailSender.java` (+implementación)
- `src/main/java/com/auth_service/auth/application/usecase/EmailNotificationListener.java` (+listener `PasswordResetEmailRequested`)
- `src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java` (+2 endpoints)
- `src/test/java/com/auth_service/auth/config/AuthTokenPropertiesTest.java` (constructor de 3 args)
- `src/test/java/com/auth_service/auth/application/usecase/ResendVerificationUseCaseTest.java` (constructor de 3 args)
- `src/test/java/com/auth_service/auth/application/usecase/RegisterAccountUseCaseTest.java` (constructor de 3 args)
- `src/test/java/com/auth_service/auth/application/usecase/TokenIssuerTest.java` (constructor de 3 args)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+2 casos `changePassword`)
- `src/test/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenRepositoryAdapterTest.java` (+caso `revokeAllForAccount`, +sobrecarga `persistAccount(email)`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (+2 `@MockitoBean`)

## Change Log

| Fecha | Cambio |
|---|---|
| 2026-07-16 | Creación de la Story 3.1 a partir de epics.md (Epic 3), con mapeo exhaustivo de la infraestructura existente de tokens de un solo uso (`VerificationToken`/`VerificationPurpose.PASSWORD_RESET`, ya declarado sin usar) y de los dos gaps reales a cerrar (`RefreshTokenRepository.revokeAllForAccount`, `Account.changePassword`). |
| 2026-07-16 | Implementación completa (Tasks 1-8): TTL configurable de recuperación, revocación de todas las Familias de Refresh Tokens de una Cuenta, mutador de contraseña en `Account`, evento/listener de email de recuperación, `RequestPasswordResetUseCase`/`ResetPasswordUseCase`, endpoints `/auth/forgot-password` y `/auth/reset-password`, y tests unitarios + de integración. Corregido en el camino un bug real de persistencia: `revokeAllForAccount` (`@Modifying(clearAutomatically = true)`) descartaba en silencio cambios de `save()` aún no flusheados cuando se llamaba después de ellos — reordenado para ser la primera escritura de la transacción. Suite completa: 230/230 tests en verde (`./mvnw -B verify`), incluyendo `ArchitectureRulesTest` y Testcontainers (Docker disponible en esta sesión). |
