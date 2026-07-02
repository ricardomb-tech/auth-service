---
baseline_commit: b8957631dfdb624e04760e6f6346d98d39f87585
---

# Story 1.3: Verificación de email y reenvío

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a titular de una Cuenta recién creada,
I want activar mi Cuenta con el enlace recibido por email,
so that pueda iniciar sesión. (FR-2)

## Acceptance Criteria

1. Con un Token de Verificación vigente, `POST /auth/verify` transiciona la Cuenta a `ACTIVE` y el token queda consumido (`consumed_at` seteado) — un segundo intento con el mismo token falla.
2. Con un token expirado, ya consumido, o inexistente, `POST /auth/verify` responde `400` `application/problem+json`; la Cuenta permanece en su estado anterior — la verificación fallida nunca activa la Cuenta.
3. Con una Cuenta `PENDING_VERIFICATION`, `POST /auth/resend-verification` emite un Token de Verificación nuevo (mismo TTL de 24h que la Story 1.2) y dispara su envío por email tras el commit; cualquier Token de Verificación anterior sin consumir de esa Cuenta queda invalidado — ya no sirve aunque no haya expirado por tiempo.
4. `POST /auth/resend-verification` responde **siempre el mismo cuerpo genérico**, exista o no la Cuenta, y también cuando la Cuenta existe pero no está `PENDING_VERIFICATION` (ya verificada, bloqueada, etc.) — anti-enumeración, mismo patrón que `POST /auth/register` de la Story 1.2.

## Tasks / Subtasks

- [x] Task 1: Extender `VerificationToken` (domain) con la operación de consumo (AC: #1, #2)
  - [x] Método de instancia `VerificationToken consume(Clock clock)`: lanza `DomainValidationException` si `consumedAt != null` ("ya utilizado") o si `Instant.now(clock)` es posterior a `expiresAt` ("expirado"); si es válido, devuelve una **nueva** instancia con `consumedAt = Instant.now(clock)` (el objeto es inmutable — no hay setters, igual que el resto de la Story 1.2).
  - [x] Método estático público `VerificationToken.hashRawToken(String rawToken)`: extrae la lógica de hash SHA-256 que hoy es un método privado (`sha256Hex`) usado solo por `issue(...)` — reutilízalo, no dupliques el algoritmo. Esta es la función que el caso de uso de verificación necesita para buscar el token por su hash.
  - [x] Tests unitarios sin Spring: 5 tests nuevos — consumo válido, doble consumo, token expirado, hash determinista, hash coincide con el de `issue(...)`. 9/9 en verde.

- [x] Task 2: Extender `Account` (domain) con la transición a `ACTIVE` (AC: #1)
  - [x] Método de instancia `void activate()`: si `status != PENDING_VERIFICATION`, lanza `DomainValidationException`; si es válido, muta `this.status = ACTIVE` (Account ya es una clase mutable desde la Story 1.2 — `status`, `failedAttempts`, `lockedUntil` no son `final`). La transición vive en el método, pero **quién la invoca** sigue siendo responsabilidad exclusiva de `application/usecase` (AD-6, AD-13) — `activate()` no se llama a sí mismo, ni decide cuándo es apropiado activarse.
  - [x] Test unitario: 4/4 en verde — `activate()` desde `PENDING_VERIFICATION` deja `ACTIVE`; `activate()` sobre una Cuenta `ACTIVE` (vía `reconstitute`) lanza `DomainValidationException`.

- [x] Task 3: Extender los ports del dominio (AC: #1, #3)
  - [x] `domain/port/VerificationTokenRepository`: añade `Optional<VerificationToken> findByTokenHash(String tokenHash)` e `int invalidateActiveTokens(AccountId accountId, VerificationPurpose purpose, Instant now)` (invalida — pone `consumed_at = now` — todo token sin consumir de esa Cuenta y ese propósito; devuelve cuántas filas afectó, útil para logs/tests, no para lógica de negocio).
  - [x] `domain/port/AccountRepository`: añade `Optional<Account> findById(AccountId id)` — `VerifyAccountUseCase` necesita cargar la Cuenta dueña del token para activarla.

- [x] Task 4: `VerifyAccountUseCase` en `application/usecase` (AC: #1, #2)
  - [x] Clase `@Service @Transactional`, constructor-injected con `VerificationTokenRepository`, `AccountRepository`, `Clock` (el mismo bean `Clock` de la Story 1.2, reutilizado).
  - [x] Método `void verify(String rawToken)` implementado exactamente en el orden documentado: hash → lookup → chequeo de propósito → `consume(clock)` + save → `findById` + `activate()` + save.
  - [x] No se tocan `failedAttempts`/`lockedUntil` — fuera de alcance (Story 3.2).
  - [x] Tests unitarios con mocks — 5/5 en verde: token válido activa y persiste; token inexistente; propósito incorrecto (`PASSWORD_RESET`); token ya consumido; token expirado — en los 4 casos de error, `AccountRepository` nunca se toca.

- [x] Task 5: `ResendVerificationUseCase` en `application/usecase` (AC: #3, #4)
  - [x] Clase `@Service @Transactional`, constructor-injected con `AccountRepository`, `VerificationTokenRepository`, `AuthTokenProperties`, `ApplicationEventPublisher`, `Clock` (reutiliza `AuthTokenProperties.verificationTtl()` de la Story 1.2).
  - [x] `ResendVerificationResult` (enum): `RESENT`, `NOT_APPLICABLE` — uso interno, el controller nunca lo expone (AC #4).
  - [x] Método `resend(String rawEmail)` implementado exactamente en el orden documentado.
  - [x] Tests unitarios con mocks — 3/3 en verde: Cuenta `PENDING_VERIFICATION` → `RESENT` + invalida + emite + publica; Cuenta inexistente → `NOT_APPLICABLE`; Cuenta `ACTIVE` → `NOT_APPLICABLE` — en ambos casos `NOT_APPLICABLE` ningún repositorio de tokens se toca.

- [x] Task 6: Adapters de persistencia — extender los ya existentes de la Story 1.2 (AC: #1, #3)
  - [x] `infrastructure/adapters/postgresql/AccountJpaRepository`: hereda `findById` de `JpaRepository`, no se necesitó añadir nada.
  - [x] `infrastructure/adapters/postgresql/AccountRepositoryAdapter`: implementa `findById(AccountId)` delegando a `jpaRepository.findById(id.value())` + el mismo mapeo `toDomain(...)` que ya existe para `findByEmail`.
  - [x] `infrastructure/adapters/postgresql/VerificationTokenJpaRepository`: añadido `findByTokenHash` (derivado) y `invalidateActiveTokens` (`@Modifying @Query` con `UPDATE ... SET consumedAt = :now WHERE accountId = :accountId AND purpose = :purpose AND consumedAt IS NULL`).
  - [x] `infrastructure/adapters/postgresql/VerificationTokenRepositoryAdapter`: implementa `findByTokenHash` (mapea con un nuevo `toDomain(...)`, apoyado en un nuevo `VerificationToken.reconstitute(...)` en el dominio — análogo a `Account.reconstitute`, no existía porque la Story 1.2 solo escribía) e `invalidateActiveTokens` (delega al `@Modifying @Query`).

- [x] Task 7: Controller REST (AC: #1, #2, #3, #4)
  - [x] `infrastructure/controller/dto/VerifyRequest(String token)` con `@NotBlank`.
  - [x] `infrastructure/controller/dto/ResendVerificationRequest(String email)` con `@NotBlank` y `@Size(max = 254)`.
  - [x] `AuthController`: `POST /auth/verify` invoca `VerifyAccountUseCase.verify(...)`, deja propagar `DomainValidationException` hacia `GlobalExceptionHandler` (sin cambios ahí) — éxito responde `200 OK` con mensaje genérico.
  - [x] `AuthController`: `POST /auth/resend-verification` invoca `ResendVerificationUseCase.resend(...)` ignorando el resultado — siempre `202 Accepted` genérico (AC #4).
  - [x] `/auth/**` ya era público desde la Story 1.2 — `SecurityConfig` no se tocó.
  - [x] Actualizado `AuthControllerTest` (slice test de la Story 1.2) con `@MockitoBean` para los 2 casos de uso nuevos que ahora forman parte del constructor de `AuthController`.

- [x] Task 8: Tests de integración (AC: #1, #2, #3, #4)
  - [x] `AuthVerificationIntegrationTest` — `@SpringBootTest` + Testcontainers + `@Transactional`. Los tokens se emiten directamente vía `VerificationToken.issue(...)` en el test (no vía `POST /auth/register`) porque el `rawToken` solo existe en memoria en el instante de emisión y el listener `AFTER_COMMIT` no dispara bajo `@Transactional` de test — mismo límite aceptado que en la Story 1.2. 4/4 en verde: token válido activa y no es reutilizable; token inexistente → 400; reenvío invalida el token viejo (verificado intentando usarlo, no vía email); reenvío con email inexistente → mismo 202 genérico.
  - [x] **Bug real encontrado y corregido en el camino:** el `@Modifying @Query` de `invalidateActiveTokens` no limpiaba el contexto de persistencia (`clearAutomatically`) — una entidad de token ya cargada en la misma transacción quedaba con `consumedAt = null` en memoria aunque el UPDATE masivo ya hubiera cambiado la fila en BD, permitiendo reutilizar un token que debía estar invalidado. Corregido con `@Modifying(clearAutomatically = true)`.

## Dev Notes

- **Continuidad con la Story 1.2 (done):** `VerificationToken`, `VerificationTokenRepository` (con solo `save` hasta ahora), `Account`, `AccountRepository`, `AuthTokenProperties`, el bean `Clock`, `VerificationEmailRequested`/`EmailNotificationListener`, y el patrón `ResultEnum` interno para anti-enumeración (`RegisterAccountResult`) ya existen — reutilízalos, no los reimplementes. Los nombres de clase de esta historia (`VerifyAccountUseCase`, `ResendVerificationUseCase`) ya estaban previstos en `ARCHITECTURE-SPINE.md#Capability-→-Architecture-Map`.
- **`VerificationToken` sigue siendo inmutable.** `consume(clock)` no muta el objeto existente — construye y devuelve uno nuevo, exactamente como el resto de los Value Objects/entidades de dominio de la Story 1.2. El caso de uso es responsable de persistir el resultado (`verificationTokenRepository.save(...)`).
- **Orden importa en `VerifyAccountUseCase`:** valida y consume el token ANTES de tocar la Cuenta. Si `consume()` lanza, `Account.activate()` nunca se ejecuta — así se garantiza AC #2 ("la Cuenta permanece en su estado anterior") por construcción, sin necesitar un `try/catch` explícito para revertir nada.
- **La verificación de propósito (`EMAIL_VERIFICATION` vs `PASSWORD_RESET`) es una guarda real, no burocracia.** `VerificationPurpose` ya tiene ambos valores desde la Story 1.2 porque el esquema los esperaba, pero `PASSWORD_RESET` no se emite todavía (llega en la Story 3.1). Sin este chequeo, el día que exista un token de recuperación de contraseña, alguien podría usarlo para "verificar" (activar) una cuenta ya activa o ajena — ciérralo ahora que es gratis.
- **Anti-enumeración solo aplica a `/auth/resend-verification`, no a `/auth/verify`.** Son historias distintas: en el registro y el reenvío, el atacante intenta *descubrir* si un email existe; en la verificación, el usuario ya *tiene* el token (se lo mandaste tú por email) — decirle "tu token expiró" no filtra nada que no supiera. No repliques el patrón de respuesta genérica en `/auth/verify`.
- **`invalidateActiveTokens` es una actualización masiva (`@Modifying @Query`), no un "leer todos y guardar uno por uno".** Es la forma correcta de invalidar en una sola sentencia SQL; no cargues los tokens a Java para volver a guardarlos.
- **Riesgo heredado de la Story 1.2 (aceptado, no bloqueante):** el lookup por `token_hash` que introduce esta historia (`findByTokenHash`) es exactamente el "future non-constant-time lookup" que quedó anotado en `deferred-work.md` — no es nuevo, ya estaba previsto; no le agregues mitigación de timing en esta historia, sigue fuera de alcance por las mismas razones documentadas ahí.

### Project Structure Notes

- Todos los archivos nuevos de esta historia caen dentro de paquetes que la Story 1.1/1.2 ya crearon — no hay paquetes nuevos.
- `VerificationTokenRepositoryAdapter` (UPDATE, no NEW): hoy solo tiene un método `save` de una sola dirección (dominio→entidad); esta historia le agrega el primer mapeo entidad→dominio (`toDomain`, análogo al que `AccountRepositoryAdapter` ya tiene).

### References

- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-5] — tokens de un solo uso hasheados, TTL
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-6] — transiciones de estado solo desde application/usecase
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso, una responsabilidad cada uno
- [Source: docs/planning-artifacts/epics.md#Story-1.3] — criterios de aceptación originales (Given/When/Then)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#41-Registro-y-Verificación-de-Email] — FR-2, NFR-2
- [Source: docs/implementation-artifacts/1-2-registro-de-cuenta-con-email-de-verificacion.md] — `VerificationToken`, `Account`, ports, `AuthTokenProperties`, bean `Clock`, patrón anti-enumeración, y los 8 patches de su code review (en particular: `Email` ya normaliza mayúsculas — no lo dupliques al comparar emails en esta historia)
- [Source: docs/implementation-artifacts/deferred-work.md] — ítem de timing en lookup por hash de token, ya aceptado como fuera de alcance

### Review Findings

_Code review 2026-07-02 — Blind Hunter + Edge Case Hunter + Acceptance Auditor (paralelo, adversarial)._

- [x] [Review][Patch] `VerificationToken.consume(clock)` llama `Instant.now(clock)` dos veces (chequeo de expiración y valor de `consumedAt`) — con un reloj real (no fijo en tests) podrían diferir por una fracción de tiempo [src/main/java/com/auth_service/auth/domain/model/VerificationToken.java] — corregido: un solo `Instant.now(clock)` reutilizado
- [x] [Review][Patch] Falta un test que confirme explícitamente que `ResendVerificationUseCase` encuentra la Cuenta sin importar mayúsculas en el email [src/test/java/com/auth_service/auth/application/usecase/ResendVerificationUseCaseTest.java] — añadido `findsAccountRegardlessOfEmailCase`, confirma que `Email` ya normaliza y no hace falta lógica adicional

- [x] [Review][Defer] Canal lateral de timing en `/auth/resend-verification` (camino `NOT_APPLICABLE` casi inmediato vs. camino `RESENT` con escritura+evento) [application/usecase/ResendVerificationUseCase.java] — deferred, misma categoría que el ítem ya aceptado de la Story 1.2 (mitigación de tiempo constante requiere diseño propio)
- [x] [Review][Defer] Condición de carrera de baja probabilidad entre un `verify` y un `resend` concurrentes sobre la misma Cuenta/token (sin lock optimista) [application/usecase/VerifyAccountUseCase.java, ResendVerificationUseCase.java] — deferred, requeriría `@Version` o lock pesimista, desproporcionado para el alcance de esta historia
- [x] [Review][Defer] `VerificationPurpose.valueOf(entity.getPurpose())` sin protección ante un valor corrupto/desconocido en la fila [infrastructure/adapters/postgresql/VerificationTokenRepositoryAdapter.java] — deferred, misma categoría que el ítem ya aceptado sobre `AccountStatus.valueOf(...)` de la Story 1.2
- [x] [Review][Defer] `ResendVerificationResult` no se loggea en ningún punto — sin esa señal, un ataque de enumeración vía reenvíos masivos (muchos `NOT_APPLICABLE`) no deja rastro observable [application/usecase/ResendVerificationUseCase.java] — deferred, pertenece a observabilidad (Epic 5), fuera de alcance de esta historia

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Tests de dominio incrementales sin Docker en cada tarea: `VerificationTokenTest` (9/9), `AccountTest` (4/4), `VerifyAccountUseCaseTest` (5/5), `ResendVerificationUseCaseTest` (3/3) — todos en verde antes de tocar el siguiente task.
- `./mvnw -B compile` tras extender los ports (Task 3) y antes de escribir los casos de uso — confirmó que los adapters de la Story 1.2 necesitaban implementar los métodos nuevos (`findById`, `findByTokenHash`, `invalidateActiveTokens`) antes de que el proyecto volviera a compilar; se implementaron en el mismo tramo (Task 6) para no dejar el build roto entre tareas.
- Suite completa sin Docker tras Task 7: 47/47 en verde (sin regresión en Stories 1.1/1.2).
- `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine ./mvnw -B -Dtest=AuthVerificationIntegrationTest test` — **primera corrida falló** (`resendOnPendingAccountInvalidatesOldTokenAndReturns202`: esperaba 400 al reusar el token viejo invalidado, recibió 200). Causa raíz real: `@Modifying @Query` de `invalidateActiveTokens` sin `clearAutomatically = true` — el UPDATE masivo cambia la fila en BD pero no sincroniza el contexto de persistencia de Hibernate; una entidad de token ya cargada en la misma transacción (el test guarda el token viejo justo antes) queda con `consumedAt = null` en memoria, y un `findByTokenHash` posterior en la misma transacción devuelve esa instancia obsoleta desde la caché de primer nivel en vez de re-consultar. Corregido añadiendo `clearAutomatically = true` — comportamiento estándar y documentado de Spring Data JPA para bulk updates.
- `DOCKER_HOST=... ./mvnw -B -Dtest=AuthVerificationIntegrationTest test` tras el fix — 4/4 en verde.
- `DOCKER_HOST=... ./mvnw -B verify` final — **58/58 tests en verde**, JaCoCo `check` sobre 19 clases de `domain/`+`application/` — **"All coverage checks have been met"**. `BUILD SUCCESS`.

### Completion Notes List

- Las 4 AC están implementadas y verificadas end-to-end (unit + integración real contra PostgreSQL vía Testcontainers).
- `VerificationToken.consume(clock)` y `Account.activate()` siguen el mismo patrón inmutable/mutable ya establecido en la Story 1.2 (`VerificationToken` devuelve una nueva instancia; `Account` muta in-place porque ya era una clase mutable desde el registro).
- Guarda de propósito añadida en `VerifyAccountUseCase` (rechaza tokens `PASSWORD_RESET`) — no estaba explícitamente pedida palabra por palabra en el AC pero sí en Dev Notes, cerrando una vulnerabilidad cruzada con la futura Story 3.1 antes de que exista.
- `invalidateActiveTokens` usa un `@Modifying @Query` (UPDATE masivo) en vez de cargar y regrabar filas una por una — y el bug real que encontré confirma por qué la nota de Dev Notes ("no cargues los tokens a Java para volver a guardarlos") era la decisión correcta: incluso hecho bien a nivel de SQL, el detalle de `clearAutomatically` es fácil de pasar por alto.
- El test de integración deliberadamente NO pasa por `POST /auth/register` para obtener el token — lo emite directamente vía `VerificationToken.issue(...)` con los repositorios reales inyectados, evitando depender del listener `AFTER_COMMIT` que no dispara bajo `@Transactional` de test (mismo límite aceptado en la Story 1.2).
- `AuthControllerTest` (slice test de la Story 1.2) tuvo que actualizarse con `@MockitoBean` para los 2 casos de uso nuevos — sin esto, Spring no podía construir `AuthController` en el contexto acotado del slice test.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/application/usecase/VerifyAccountUseCase.java`
- `src/main/java/com/auth_service/auth/application/usecase/ResendVerificationUseCase.java`
- `src/main/java/com/auth_service/auth/application/usecase/ResendVerificationResult.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/VerifyRequest.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/ResendVerificationRequest.java`
- `src/test/java/com/auth_service/auth/application/usecase/VerifyAccountUseCaseTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/ResendVerificationUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthVerificationIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/model/VerificationToken.java` (`consume`, `hashRawToken`, `reconstitute`)
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (`activate`)
- `src/main/java/com/auth_service/auth/domain/port/VerificationTokenRepository.java` (`findByTokenHash`, `invalidateActiveTokens`)
- `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java` (`findById`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java` (`findById`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/VerificationTokenJpaRepository.java` (`findByTokenHash`, `invalidateActiveTokens` con `clearAutomatically`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/VerificationTokenRepositoryAdapter.java` (`findByTokenHash`, `invalidateActiveTokens`, mapeo `toDomain`)
- `src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java` (`/auth/verify`, `/auth/resend-verification`)
- `src/test/java/com/auth_service/auth/domain/model/VerificationTokenTest.java` (tests de `consume`/`hashRawToken`)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (tests de `activate`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (`@MockitoBean` para los 2 casos de uso nuevos)
