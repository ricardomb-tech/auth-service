---
baseline_commit: d2f95cb77e2974ce438337b207931a8cac19cf81
---

# Story 1.2: Registro de cuenta con email de verificación

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a visitante,
I want crear una Cuenta con mi email y una contraseña,
so that pueda acceder al ecosistema tras verificar mi identidad. (FR-1)

## Acceptance Criteria

1. Con un email no registrado y una contraseña que cumple la política (≥8 caracteres, mayúscula, minúscula, dígito), `POST /auth/register` crea una Cuenta `PENDING_VERIFICATION` con Rol `USER`, la contraseña se almacena solo como hash BCrypt, se genera un Token de Verificación (hash SHA-256 en BD, TTL 24h) y se dispara el envío del email **después** del commit de la transacción. La migración de esta historia crea `accounts`, `account_roles` y `verification_tokens`.
2. Con un email ya registrado, `POST /auth/register` responde **exactamente igual** (mismo status, mismo cuerpo) que un registro exitoso — no se crea una segunda Cuenta, no se emite un nuevo token, no hay forma de distinguir ambos casos desde la respuesta HTTP.
3. Con una contraseña que incumple la política o un email con formato inválido, `POST /auth/register` responde `400` `application/problem+json` con el detalle de la regla incumplida (esto SÍ es distinguible de éxito — es un error de validación de entrada, no una filtración de existencia de cuenta).
4. `domain/` sigue sin depender de Spring ni JPA: el test `ArchitectureRulesTest` de la Story 1.1 debe seguir pasando (ahora contra clases reales, no vacías) sin modificarlo.

## Tasks / Subtasks

- [x] Task 1: Value Objects y modelos de dominio puros en `domain/model` (AC: #1, #3, #4)
  - [x] `Email` (record): valida formato en el constructor compacto; lanza `DomainValidationException` si es inválido. Sin imports de Spring/Jakarta.
  - [x] `RawPassword` (record): valida la política (≥8 caracteres, ≥1 mayúscula, ≥1 minúscula, ≥1 dígito) en el constructor compacto; lanza `DomainValidationException` con el detalle de la regla incumplida si falla. Representa la contraseña en claro **solo de tránsito** — nunca se persiste ni se loggea (AD-5).
  - [x] `HashedPassword` (record): wrapper simple sobre el hash ya calculado (valida solo no-blank); el hashing en sí NO ocurre aquí (ver Task 2, `PasswordHasher`).
  - [x] `AccountId` (record): wrapper sobre `UUID`; factory estático `AccountId.newId()` usando `UUID.randomUUID()` — el ID se genera en la app, no en la BD (Stack/Consistency Conventions del spine).
  - [x] `Role` (enum): `USER`, `ADMIN`.
  - [x] `AccountStatus` (enum): `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, `DISABLED`.
  - [x] `VerificationPurpose` (enum): `EMAIL_VERIFICATION`, `PASSWORD_RESET` — ambos valores existen ya porque el esquema los espera (ver ERD del spine), aunque esta historia solo emite `EMAIL_VERIFICATION`.
  - [x] `Account` (clase, no record — es mutable a través de su ciclo de vida en historias futuras): campos `AccountId id`, `Email email`, `HashedPassword passwordHash` (nullable — null para cuentas que en el futuro sean solo federadas, Epic 2), `AccountStatus status`, `Set<Role> roles`, `int failedAttempts`, `Instant lockedUntil` (nullable), `Instant createdAt`. Factory estático `Account.register(Email email, HashedPassword passwordHash)` → `Account` con `status = PENDING_VERIFICATION`, `roles = {USER}`, `failedAttempts = 0`. Sin setters públicos arbitrarios — solo lo que este factory necesita por ahora (las transiciones de estado llegan en historias futuras, AD-6). Se añadió también `Account.reconstitute(...)` para que el adapter de persistencia pueda mapear filas existentes de vuelta a dominio.
  - [x] `VerificationToken` (clase): campos `id` (UUID), `AccountId accountId`, `String tokenHash` (SHA-256 hex), `VerificationPurpose purpose`, `Instant expiresAt`, `Instant consumedAt` (nullable, null = no consumido). Factory estático `VerificationToken.issue(AccountId accountId, VerificationPurpose purpose, Duration ttl, Clock clock)` que:
    1. Genera un token aleatorio crudo con `SecureRandom` (JDK puro, no Spring — permitido en domain).
    2. Calcula su hash SHA-256 con `java.security.MessageDigest` (JDK puro).
    3. Devuelve un objeto contenedor `VerificationToken.Issued(String rawToken, VerificationToken token)` — el `rawToken` es lo único que se envía por email; `token` (con el hash) es lo único que se persiste. **Nunca** guardar `rawToken`.
  - [x] `domain/exception/DomainValidationException` (RuntimeException): mensaje descriptivo de la regla incumplida; la usan `Email` y `RawPassword`.
  - [x] Tests: `EmailTest`, `RawPasswordTest`, `AccountTest`, `VerificationTokenTest` — 18/18 en verde, sin Spring.

- [x] Task 2: Ports del dominio en `domain/port` (AC: #1)
  - [x] `AccountRepository`: `Account save(Account account)`, `Optional<Account> findByEmail(Email email)`.
  - [x] `VerificationTokenRepository`: `VerificationToken save(VerificationToken token)`.
  - [x] `EmailSender`: `void sendVerificationEmail(Email recipient, String rawToken)`.
  - [x] `PasswordHasher`: `HashedPassword hash(RawPassword rawPassword)`. (El método `matches(...)` para login lo añade la Story 1.4 — no lo anticipes aquí.)

- [x] Task 3: `RegisterAccountUseCase` en `application/usecase` (AC: #1, #2, #3)
  - [x] Clase `@Service` `@Transactional`, constructor-injected con `AccountRepository`, `VerificationTokenRepository`, `PasswordHasher`, `AuthTokenProperties` (Task 4), `ApplicationEventPublisher`.
  - [x] Método `RegisterAccountResult register(RegisterAccountCommand command)` donde `RegisterAccountCommand(String rawEmail, String rawPassword)` es un `record` de entrada (construye `Email`/`RawPassword` **dentro** del caso de uso — la validación de formato ocurre ahí, propagando `DomainValidationException` si falla).
  - [x] `RegisterAccountResult` (enum): `ACCOUNT_CREATED`, `EMAIL_ALREADY_REGISTERED` — uso interno (logging/tests), **el controller nunca lo expone en la respuesta HTTP** (AC #2).
  - [x] Lógica: si `accountRepository.findByEmail(email)` ya existe → devolver `EMAIL_ALREADY_REGISTERED` sin persistir nada ni publicar el evento de email. Si no existe → `passwordHasher.hash(rawPassword)` → `Account.register(email, hashed)` → `accountRepository.save(account)` → `VerificationToken.issue(account.id(), EMAIL_VERIFICATION, authTokenProperties.verificationTtl(), Clock.systemUTC())` → `verificationTokenRepository.save(issued.token())` → `applicationEventPublisher.publishEvent(new VerificationEmailRequested(email, issued.rawToken()))` → devolver `ACCOUNT_CREATED`.
  - [x] **No** publiques un Domain Event (`domain/event`, patrón outbox de AD-15) en esta historia — eso es exclusivo de la Story 6.1, que extenderá este mismo caso de uso más adelante. El evento de esta historia es un mecanismo puramente interno de Spring para diferir el envío de email hasta después del commit (ver Task 4), no tiene relación con integración entre servicios.
  - [x] Tests: `RegisterAccountUseCaseTest` con mocks de los 4 ports — 2/2 en verde (cuenta nueva: `save`+evento publicado; email existente: ningún `save` ni evento).

- [x] Task 4: Envío de email después del commit (AC: #1)
  - [x] `application/usecase/VerificationEmailRequested` (record): `Email recipient`, `String rawToken`. Vive en `application/`, no en `domain/event` — es un detalle de orquestación de este caso de uso, no un Evento de Dominio del spine.
  - [x] `application/usecase/EmailNotificationListener` (`@Component`): método anotado `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` que recibe `VerificationEmailRequested` y llama a `emailSender.sendVerificationEmail(event.recipient(), event.rawToken())`. Si el envío falla, el fallo se loggea pero **no** revierte ni afecta la transacción ya confirmada (AD-9) — el listener corre después del commit, así que estructuralmente no puede revertir nada.
  - [x] `config/AuthTokenProperties` (`@ConfigurationProperties(prefix = "auth.token")`, `record` con default): `Duration verificationTtl` (default `PT24H` vía `@DefaultValue` o valor por defecto en `application.properties`: `auth.token.verification-ttl=24h`). Nunca `@Value` disperso (AD-10). Se añadió `@ConfigurationPropertiesScan` en `AuthServiceApplication` para que Spring registre el `record`.

- [x] Task 5: Adapters de persistencia en `infrastructure/adapters/postgresql` (AC: #1)
  - [x] `AccountEntity` (`@Entity @Table(name = "accounts")`): `id` (UUID, `@Id`), `email` (String, unique), `passwordHash` (String, nullable), `status` (String), `failedAttempts` (int), `lockedUntil` (Instant, nullable), `createdAt` (Instant). Roles vía `@ElementCollection` mapeando `Set<Role>` (el enum de dominio) directamente — infra puede importar domain sin violar AD-1.
  - [x] `AccountJpaRepository extends JpaRepository<AccountEntity, UUID>` con `Optional<AccountEntity> findByEmail(String email)`.
  - [x] `AccountRepositoryAdapter implements AccountRepository`: mapea `Account` (dominio) ↔ `AccountEntity` (JPA) — el dominio nunca ve `AccountEntity`.
  - [x] `VerificationTokenEntity` (`@Entity @Table(name = "verification_tokens")`): `id` (UUID), `accountId` (UUID), `tokenHash` (String, unique), `purpose` (String), `expiresAt` (Instant), `consumedAt` (Instant, nullable).
  - [x] `VerificationTokenJpaRepository extends JpaRepository<VerificationTokenEntity, UUID>`.
  - [x] `VerificationTokenRepositoryAdapter implements VerificationTokenRepository`.

- [x] Task 6: Adapters restantes (AC: #1)
  - [x] `infrastructure/adapters/email/LoggingEmailSender implements EmailSender` (`@Component`): loggea (nivel INFO) el destinatario y el enlace de verificación construido con `APP_BASE_URL` + el `rawToken`. Única implementación de `EmailSender` en esta historia — no hay adapter SMTP todavía.
  - [x] `infrastructure/adapters/security/BCryptPasswordHasher implements PasswordHasher` (`@Component`): envuelve un bean `PasswordEncoder` de Spring Security (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`, default BCrypt — AD-5). El bean `PasswordEncoder` se registró en `config/PasswordEncoderConfig` (clase nueva, para no tocar `SecurityConfig` más allá de `PUBLIC_ENDPOINTS`).

- [x] Task 7: Migración Flyway `V2__accounts.sql` (AC: #1) — *checkbox corregido tras code review: la migración ya estaba implementada correctamente, solo faltaba marcarla.*
  - [x] Tabla `accounts`: `id uuid PRIMARY KEY`, `email text NOT NULL UNIQUE`, `password_hash text`, `status text NOT NULL`, `failed_attempts int NOT NULL DEFAULT 0`, `locked_until timestamptz`, `created_at timestamptz NOT NULL DEFAULT now()`.
  - [x] Tabla `account_roles`: `account_id uuid NOT NULL REFERENCES accounts(id)`, `role text NOT NULL`, `PRIMARY KEY (account_id, role)`.
  - [x] Tabla `verification_tokens`: `id uuid PRIMARY KEY`, `account_id uuid NOT NULL REFERENCES accounts(id)`, `token_hash text NOT NULL UNIQUE`, `purpose text NOT NULL`, `expires_at timestamptz NOT NULL`, `consumed_at timestamptz`, `created_at timestamptz NOT NULL DEFAULT now()`.

- [x] Task 8: Controller REST (AC: #1, #2, #3)
  - [x] `infrastructure/controller/AuthController` (`@RestController`, `@RequestMapping("/auth")`): `POST /auth/register` recibe `RegisterRequest(String email, String password)` (record DTO en `infrastructure/controller/dto`, con `@NotBlank` de `jakarta.validation`), invoca `RegisterAccountUseCase.register(...)`, e **ignora el resultado para efectos de la respuesta**: siempre responde `202 Accepted` con `{"message": "Si el email es válido, recibirás un correo de verificación."}` sin importar si `RegisterAccountResult` fue `ACCOUNT_CREATED` o `EMAIL_ALREADY_REGISTERED` (AC #2).
  - [x] Extendido `SecurityConfig.PUBLIC_ENDPOINTS` (Story 1.1) con el patrón `/auth/**` completo — convención ya declarada en el spine (Consistency Conventions, fila "Rutas"), evita tocar `SecurityConfig` en cada historia subsiguiente de Epic 1.

- [x] Task 9: Manejo de errores de validación (AC: #3)
  - [x] `infrastructure/controller/GlobalExceptionHandler` (`@RestControllerAdvice`, extiende `ResponseEntityExceptionHandler` para heredar el manejo de `MethodArgumentNotValidException` de `@Valid`). Mapea `DomainValidationException` → `400` `application/problem+json` con el mensaje de la excepción como `detail` (AD-8).
  - [x] Mismo patrón de construcción de `ProblemDetail` (`ProblemDetail.forStatusAndDetail`) que `SecurityConfig.writeProblemDetail`, documentado en el Javadoc de la clase.

- [x] Task 10: Tests (AC: #1, #2, #3, #4)
  - [x] Unit tests de dominio sin Spring: `Email`, `RawPassword` (casos válidos/inválidos de cada regla de la política), `Account.register`, `VerificationToken.issue` (el hash persistido nunca es igual al `rawToken` devuelto).
  - [x] Unit test de `RegisterAccountUseCase` con mocks de los 4 ports — casos: cuenta nueva (verifica que se llama a `save` una vez, se publica el evento) y email ya existente (verifica que **no** se llama a `save` ni se publica el evento).
  - [x] Integration test (`@SpringBootTest` + Testcontainers, como en Story 1.1) de `POST /auth/register`: caso feliz (202 y fila real en `accounts`/`verification_tokens`), email duplicado (misma respuesta, sigue habiendo solo 1 fila en `accounts`), contraseña débil (400 problem+json), email inválido (400 problem+json).
  - [x] `ArchitectureRulesTest` sigue en verde con las clases reales de `domain/` y `application/` — confirmado, no era vacua.
  - [x] **Ajuste no anticipado:** `SecurityConfigTest` (Story 1.1) dejó de arrancar porque `@WebMvcTest` sin `controllers` específico ahora escanea también `AuthController` y arrastra sus dependencias reales. Se acotó a `@WebMvcTest(controllers = SecurityConfigTest.NoOpController.class)` con un controller vacío declarado en el propio test — mantiene el propósito original (solo probar el filtro de seguridad) sin acoplarse a `AuthController`.

## Dev Notes

- **Continuidad con la Story 1.1 (done):** el esqueleto de paquetes ya existe (`domain/{model,event,port,exception}`, `application/usecase/`, `infrastructure/{controller,adapters/{postgresql,email,oauth,messaging}}`, `config/`). `SecurityConfig` ya existe con deny-all + `ProblemDetail` para 401/403 vía `AuthenticationEntryPoint`/`AccessDeniedHandler` — **no toques esa parte**, esta historia solo le añade rutas a `PUBLIC_ENDPOINTS`. El `pom.xml` ya trae `spring-boot-starter-validation`, `spring-boot-starter-mail` (sin usar aún — lo consumirá el adapter SMTP de una historia futura), Flyway, y todo lo demás — no dupliques dependencias. `V1__init.sql` ya existe (solo `pgcrypto`); esta historia añade `V2__accounts.sql`.
- **`ArchitectureRulesTest` deja de ser vacuo con esta historia.** Ya no corre con `allowEmptyShould` "gratis" — ahora hay clases reales en `domain/` y `application/` que la regla evalúa de verdad. Si el test falla, es una señal real de que algo en `domain/model`, `domain/port` o `domain/exception` quedó importando Spring/JPA por error, o que `application/usecase` quedó importando `infrastructure/`.
- **No confundas los dos tipos de "evento" en esta historia.** `VerificationEmailRequested` (Task 4) es un `ApplicationEvent` de Spring, interno al proceso, solo para diferir el envío de email hasta después del commit (AD-9). Los **Eventos de Dominio** de verdad (`AccountRegistered`, en `domain/event`, con Transactional Outbox — AD-15) NO se implementan en esta historia; esa es la Story 6.1, que reutilizará `RegisterAccountUseCase` extendiéndolo. No crees nada en `domain/event/` todavía.
- **Anti-enumeración es responsabilidad del controller, no del caso de uso.** `RegisterAccountResult` existe para que el caso de uso comunique internamente qué pasó (útil para logs/tests), pero `AuthController` debe ignorar ese resultado al construir la respuesta HTTP — ambos casos devuelven exactamente el mismo `status` y `body`.
- **`RawPassword` nunca se loggea ni se persiste.** Solo `HashedPassword` (el resultado de `PasswordHasher.hash(...)`) llega a la base de datos. Verifica que ningún `logger.info(...)`/`toString()` implícito de `Account`/`RegisterAccountCommand` exponga la contraseña en claro.
- **TTLs configurables, no hardcodeados** (AD-10, NFR-7): `AuthTokenProperties` es el único lugar con el valor de 24h; no repitas `Duration.ofHours(24)` en otro archivo.

### Project Structure Notes

- Alineado con `ARCHITECTURE-SPINE.md#Structural-Seed` y con la estructura ya creada por la Story 1.1. Archivos nuevos de esta historia, todos dentro de paquetes que ya existían vacíos (excepto `infrastructure/controller/dto/` y `infrastructure/adapters/security/`, subpaquetes nuevos pero coherentes con el árbol declarado).
- `SecurityConfig.java` (UPDATE, no NEW): añade `/auth/**` a `PUBLIC_ENDPOINTS`; no cambies nada más de esa clase.
- `application.properties`/`application-dev.properties` (UPDATE): añade `auth.token.verification-ttl=24h` y `APP_BASE_URL` si `LoggingEmailSender` lo necesita para construir el link (ya está en `.env.example` de la Story 1.1, pero aún no consumido por ningún código — esta historia es la primera en leerlo).

### References

- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-5] — hashing BCrypt, tokens de un solo uso hasheados SHA-256, política de contraseña
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-9] — email detrás de port, envío tras commit
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-12] — regla de dependencias enforced con ArchUnit (Story 1.1)
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso en `application/usecase`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-14] — Value Objects
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-15] — Domain Events + Outbox (NO aplica a esta historia, ver Dev Notes)
- [Source: docs/planning-artifacts/epics.md#Story-1.2] — criterios de aceptación originales (Given/When/Then)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#41-Registro-y-Verificación-de-Email] — FR-1, NFR-1, NFR-2
- [Source: docs/implementation-artifacts/1-1-servicio-ejecutable-con-clean-architecture-y-gate-de-calidad.md] — estructura ya creada, `SecurityConfig`, versiones ya fijadas en `pom.xml` (jjwt 0.13.0 no se usa todavía — es de la Story 1.4)

### Review Findings

_Code review 2026-07-02 — Blind Hunter + Edge Case Hunter + Acceptance Auditor (paralelo, adversarial)._

- [x] [Review][Patch] Condición de carrera (TOCTOU): dos registros concurrentes con el mismo email pasan el `findByEmail` antes de que el primero haga commit, y el segundo `save()` choca contra el `UNIQUE` de `accounts.email` sin manejo — hoy propaga como excepción no capturada, rompiendo la garantía anti-enumeración de AC #2 en el peor momento posible [src/main/java/com/auth_service/auth/application/usecase/RegisterAccountUseCase.java] — corregido: `AuthController` captura `DataIntegrityViolationException` y responde el mismo 202 genérico (deja que la transacción del caso de uso se revierta limpio, en vez de intentar continuar una transacción envenenada); test `AuthControllerTest.concurrentDuplicateRegistrationStillReturns202`
- [x] [Review][Patch] `GlobalExceptionHandler` no tiene un handler de respaldo — cualquier excepción que no sea `DomainValidationException` (incluida la del punto anterior) cae al manejo por defecto de Spring, con riesgo de exponer detalles internos [src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java] — corregido: `@ExceptionHandler(Exception.class)` → 500 `problem+json` genérico, con log del detalle real
- [x] [Review][Patch] `Email` no normaliza mayúsculas/minúsculas — `User@Example.com` y `user@example.com` se trataban como cuentas distintas, rompiendo de facto la unicidad de email [src/main/java/com/auth_service/auth/domain/model/Email.java] — corregido: normaliza a minúsculas + trim en el constructor compacto; tests `EmailTest.normalizesToLowercaseAndTrims`/`twoEmailsDifferingOnlyByCaseAreEqual`
- [x] [Review][Patch] `RawPassword` no tenía longitud máxima — BCrypt trunca silenciosamente a 72 bytes [src/main/java/com/auth_service/auth/domain/model/RawPassword.java] — corregido: rechaza >72 caracteres; además `@Size` en `RegisterRequest` como defensa en profundidad; tests `RawPasswordTest.rejectsLongerThan72Characters`/`accepts72Characters`
- [x] [Review][Patch] `RegisterAccountUseCase` llamaba a `Clock.systemUTC()` inline en vez de recibir un `Clock` inyectado [src/main/java/com/auth_service/auth/application/usecase/RegisterAccountUseCase.java] — corregido: `Clock` inyectado por constructor, bean definido en `AuthServiceApplication`
- [x] [Review][Patch] `LoggingEmailSender` no tenía guarda de perfil [src/main/java/com/auth_service/auth/infrastructure/adapters/email/LoggingEmailSender.java] — corregido: `@Profile("!prod")` — en `prod` sin un `EmailSender` real, el arranque falla en vez de loggear tokens en claro
- [x] [Review][Patch] `VerificationToken.issue` no validaba que `ttl` fuera positivo [src/main/java/com/auth_service/auth/domain/model/VerificationToken.java] — corregido: `IllegalArgumentException` si `ttl` es nulo/cero/negativo; tests `VerificationTokenTest.rejectsNullTtl`/`rejectsZeroOrNegativeTtl`
- [x] [Review][Patch] `RegisterAccountCommand` no tenía `toString()` propio — expondría la contraseña en claro si algo lo loggeara [src/main/java/com/auth_service/auth/application/usecase/RegisterAccountCommand.java] — corregido: `toString()` redacta `rawPassword`

- [x] [Review][Defer] Canal lateral de timing: el camino "email ya existe" responde casi de inmediato, el camino "cuenta nueva" tarda lo que cuesta un hash BCrypt — un atacante podría distinguir ambos casos por latencia pese al cuerpo de respuesta idéntico [application/usecase/RegisterAccountUseCase.java] — deferred, mitigación (hash señuelo en el camino duplicado) requiere diseño propio, no bloquea el MVP
- [x] [Review][Defer] `sha256Hex`/comparación de hash de token no es de tiempo constante — no se ejecuta código de búsqueda por hash todavía (eso es la Story 1.3) [domain/model/VerificationToken.java] — deferred a cuando exista el lookup real
- [x] [Review][Defer] `AccountStatus.valueOf(entity.getStatus())` no está protegido contra un valor corrupto/desconocido en la fila [infrastructure/adapters/postgresql/AccountRepositoryAdapter.java] — deferred, hoy es inalcanzable (solo `PENDING_VERIFICATION` se escribe); revisar cuando existan más transiciones (Story 3.2)
- [x] [Review][Defer] `LoggingEmailSender` no sanea el token/email antes de loggear (riesgo teórico de log injection vía caracteres de control) [infrastructure/adapters/email/LoggingEmailSender.java] — deferred, adapter de desarrollo temporal, se reemplaza cuando llegue el adapter SMTP real

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -B -Dtest=EmailTest,RawPasswordTest,AccountTest,VerificationTokenTest -Djacoco.skip=true test` — 18/18 en verde (Task 1, dominio puro sin Spring).
- `./mvnw -B -Dtest=RegisterAccountUseCaseTest -Djacoco.skip=true test` — 2/2 en verde (Task 3, mocks de los 4 ports).
- `./mvnw -B -Dtest=ArchitectureRulesTest -Djacoco.skip=true test` — 3/3 en verde con clases reales de `domain/`/`application/` (confirma que la regla de la Story 1.1 no era vacua).
- Regresión detectada al compilar: `SecurityConfigTest` (Story 1.1) dejó de arrancar porque `@WebMvcTest` sin `controllers` explícito ahora escanea también `AuthController` (nuevo en esta historia) y arrastra su cadena de dependencias real (casos de uso, repositorios JPA) dentro de un slice test que no las tiene disponibles. Fix: `@WebMvcTest(controllers = SecurityConfigTest.NoOpController.class)` con un controller vacío declarado en el propio test — mantiene el test acotado a la seguridad, no a un endpoint de negocio.
- `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine ./mvnw -B -Dtest=AuthControllerIntegrationTest -Djacoco.skip=true test` — 4/4 en verde: registro nuevo (fila real en `accounts`+`verification_tokens`, status `PENDING_VERIFICATION`), email duplicado (misma respuesta, 1 sola fila), contraseña débil (400 problem+json), email inválido (400 problem+json). El pin de `DOCKER_HOST` de la Story 1.1 (Docker Desktop expone dos pipes en Windows) sigue siendo necesario en este entorno.
- `DOCKER_HOST=... ./mvnw -B verify` final — **32/32 tests en verde**, `jacoco-check` analizó 16 clases de `domain/`+`application/` y **"All coverage checks have been met"** (el gate del 80%, Story 1.1, pasa por primera vez con contenido real, no vacuo). `BUILD SUCCESS`.

### Completion Notes List

- Las 4 AC están implementadas y verificadas end-to-end (unit + integración real contra PostgreSQL vía Testcontainers).
- Diseño de Value Objects: `RawPassword`/`HashedPassword` sobrescriben `toString()` para no exponer la contraseña en logs accidentales (no pedido explícitamente por la historia, pero directamente derivado de AD-5 "prohibido loggear contraseñas").
- El hashing de contraseña vive detrás del port `PasswordHasher` (domain) implementado por `BCryptPasswordHasher` (infra), que envuelve un bean `PasswordEncoder` de Spring Security registrado en una clase nueva `config/PasswordEncoderConfig` — deliberadamente separada de `SecurityConfig` para respetar el límite que la Story 1.1 dejó documentado en Dev Notes.
- `GlobalExceptionHandler` (ausente desde la Story 1.1 a propósito) ahora existe, extiende `ResponseEntityExceptionHandler` para heredar el manejo estándar de Spring de `MethodArgumentNotValidException` (fallos de `@Valid`) como Problem Details, y añade el mapeo de `DomainValidationException`.
- El evento `VerificationEmailRequested` es un `ApplicationEvent` interno de Spring (diferir el email hasta después del commit, AD-9) — deliberadamente distinto del futuro Domain Event `AccountRegistered` (AD-15, Story 6.1); no se creó nada en `domain/event/` en esta historia.
- Efecto secundario documentado: `SecurityConfigTest` (Story 1.1) tuvo que actualizarse para acotar su `@WebMvcTest` — ver Debug Log References. Es un ajuste de alcance del test, no un cambio de comportamiento de `SecurityConfig`.
- Se fijó explícitamente `jacoco-maven-plugin` a la versión `0.8.15` en `pom.xml` (Maven advertía que faltaba, aunque resolvía a esa versión por defecto) — hallazgo menor descubierto al correr los tests de esta historia, corregido de paso.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/domain/exception/DomainValidationException.java`
- `src/main/java/com/auth_service/auth/domain/model/{Email,RawPassword,HashedPassword,AccountId,Role,AccountStatus,VerificationPurpose,Account,VerificationToken}.java`
- `src/main/java/com/auth_service/auth/domain/port/{AccountRepository,VerificationTokenRepository,EmailSender,PasswordHasher}.java`
- `src/main/java/com/auth_service/auth/application/usecase/{RegisterAccountCommand,RegisterAccountResult,RegisterAccountUseCase,VerificationEmailRequested,EmailNotificationListener}.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/{AccountEntity,AccountJpaRepository,AccountRepositoryAdapter,VerificationTokenEntity,VerificationTokenJpaRepository,VerificationTokenRepositoryAdapter}.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/email/LoggingEmailSender.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/security/BCryptPasswordHasher.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/{AuthController,GlobalExceptionHandler}.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/{RegisterRequest,MessageResponse}.java`
- `src/main/java/com/auth_service/auth/config/{AuthTokenProperties,PasswordEncoderConfig}.java`
- `src/main/resources/db/migration/V2__accounts.sql`
- `src/test/java/com/auth_service/auth/domain/model/{EmailTest,RawPasswordTest,AccountTest,VerificationTokenTest}.java`
- `src/test/java/com/auth_service/auth/application/usecase/RegisterAccountUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerIntegrationTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (post-review: slice test de la carrera de registro concurrente)

**Modificados:**
- `src/main/java/com/auth_service/auth/AuthServiceApplication.java` (`@ConfigurationPropertiesScan`; post-review: bean `Clock`)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (`/auth/**` público)
- `src/main/resources/application.properties` (`auth.token.verification-ttl`)
- `src/test/java/com/auth_service/auth/config/SecurityConfigTest.java` (acotar `@WebMvcTest` para no arrastrar `AuthController`)
- `pom.xml` (versión explícita de `jacoco-maven-plugin`)
- **Post-review:** `AuthController.java` (captura `DataIntegrityViolationException`), `GlobalExceptionHandler.java` (catch-all), `Email.java` (normalización), `RawPassword.java` (longitud máxima), `RegisterRequest.java` (`@Size`), `RegisterAccountUseCase.java` (`Clock` inyectado), `LoggingEmailSender.java` (`@Profile`), `VerificationToken.java` (validación de `ttl`), `RegisterAccountCommand.java` (`toString()`), `RegisterAccountUseCaseTest.java`/`RawPasswordTest.java`/`EmailTest.java`/`VerificationTokenTest.java` (tests de los patches)
