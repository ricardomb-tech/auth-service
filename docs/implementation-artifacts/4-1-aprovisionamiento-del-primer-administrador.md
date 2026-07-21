---
baseline_commit: 5b62aae5feb774dfb46a0ebec808bcb430b3a169
---

# Story 4.1: Aprovisionamiento del primer administrador

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a operador del servicio,
I want que el primer arranque cree la Cuenta ADMIN inicial desde configuración,
so that nunca tenga que tocar la base de datos a mano. (FR-12)

## Acceptance Criteria

1. Dado un arranque sin ninguna Cuenta con Rol `ADMIN` en la base de datos, cuando la aplicación inicia con `AUTH_ADMIN_EMAIL` y `AUTH_ADMIN_PASSWORD` definidos, entonces se crea una Cuenta `ACTIVE` con Roles `ADMIN` y `USER` y contraseña BCrypt (AD-10, NFR-7).
2. Dado un arranque donde ya existe al menos una Cuenta con Rol `ADMIN`, cuando la aplicación inicia, entonces no se crea ni modifica ninguna Cuenta — independientemente de si `AUTH_ADMIN_EMAIL`/`AUTH_ADMIN_PASSWORD` están definidos o no.
3. Dado un arranque sin `AUTH_ADMIN_EMAIL`/`AUTH_ADMIN_PASSWORD` definidos y sin ningún `ADMIN` existente, cuando la aplicación inicia, entonces se registra una advertencia clara en logs y el servicio arranca igualmente (sin fallar el arranque).

## Tasks / Subtasks

- [x] Task 1: `Account.registerAdmin` — factory de dominio para el Administrador inicial (AC: #1)
  - [x] Añadir a `src/main/java/com/auth_service/auth/domain/model/Account.java`, junto a `register`/`registerFederated` (mismo estilo, mismo bloque de campos por defecto: `failedAttempts=0`, `lockedUntil=null`, `createdAt=Instant.now()`):
    ```java
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
    ```
  - [x] Test nuevo en `AccountTest`: `registerAdminCreatesActiveAccountWithAdminAndUserRoles` — verifica `status() == ACTIVE`, `roles()` contiene exactamente `{ADMIN, USER}`, `failedAttempts() == 0`, `lockedUntil() == null`, `id()`/`createdAt()` no nulos (mismo shape que `registerCreatesAccountPendingVerificationWithUserRole`).

- [x] Task 2: `AccountRepository` — comprobar existencia de un `ADMIN` (AC: #1, #2, #3)
  - [x] Añadir al port `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java`:
    ```java
    boolean existsByRole(Role role);
    ```
    (import `com.auth_service.auth.domain.model.Role`). Es el único método nuevo que necesita el port — no hace falta paginación ni listado, la única pregunta que esta historia necesita responder es "¿existe al menos un ADMIN?".
  - [x] Añadir a `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountJpaRepository.java`:
    ```java
    boolean existsByRolesContaining(Role role);
    ```
    Spring Data JPA traduce `Containing` sobre la colección `roles` (`@ElementCollection` en `AccountEntity`, ver `V1__init.sql`/`accounts`/`account_roles`) a un `MEMBER OF` — no hace falta `@Query` manual.
  - [x] Implementar en `AccountRepositoryAdapter.java`:
    ```java
    @Override
    public boolean existsByRole(Role role) {
        return jpaRepository.existsByRolesContaining(role);
    }
    ```
  - [x] No hace falta ningún test unitario aislado para el adapter (este proyecto no los tiene para el resto de `AccountRepositoryAdapter` — se cubre por la integración de Task 5).

- [x] Task 3: `AdminBootstrapProperties` — configuración opcional del Administrador inicial (AC: #1, #3, NFR-7)
  - [x] Nuevo record en `config/`, **con una diferencia deliberada respecto al patrón de `LockoutProperties`/`AuthTokenProperties`/`JwtProperties`**: esos tres fallan rápido (`IllegalStateException`) si falta un valor obligatorio; este NO debe fallar si faltan `email`/`password` — AC #3 exige que el servicio arranque igual, solo con una advertencia en logs (la decisión de "advertir o no" la toma el runner de Task 4, no esta clase):
    ```java
    package com.auth_service.auth.config;

    import org.springframework.boot.context.properties.ConfigurationProperties;

    /**
     * Credenciales del Administrador inicial (FR-12, AD-10, NFR-7) — nunca
     * {@code @Value} disperso. {@code auth.admin.email}/{@code password} en
     * {@code application.properties}, mapeadas desde {@code AUTH_ADMIN_EMAIL}/
     * {@code AUTH_ADMIN_PASSWORD}. A diferencia de {@link JwtProperties} o
     * {@link AuthTokenProperties}, ambos valores son opcionales a nivel de
     * esta clase — el arranque sin ellos es un escenario válido (AC #3 de la
     * Story 4.1); quien decide si eso amerita una advertencia es
     * {@code AdminBootstrapRunner}, no este record.
     */
    @ConfigurationProperties(prefix = "auth.admin")
    public record AdminBootstrapProperties(String email, String password) {
    }
    ```
  - [x] Nuevas propiedades en `application.properties`, nuevo bloque después de `auth.lockout.*` (mismo patrón que `auth.jwt.secret-previous=${JWT_SECRET_PREVIOUS:}` para un valor opcional con default vacío):
    ```properties
    # ===============================
    # ADMINISTRADOR INICIAL (FR-12, NFR-7 — opcional, ver AdminBootstrapProperties)
    # ===============================
    auth.admin.email=${AUTH_ADMIN_EMAIL:}
    auth.admin.password=${AUTH_ADMIN_PASSWORD:}
    ```
  - [x] Test nuevo `AdminBootstrapPropertiesTest` (unit, sin Spring, igual que `LockoutPropertiesTest`/`AuthTokenPropertiesTest` en estilo aunque no haya validación que probar): construcción con `email`/`password` nulos no lanza excepción; construcción con ambos valores los expone tal cual vía los accessors del record.

- [x] Task 4: `ProvisionInitialAdminUseCase` — orquestación transaccional (AC: #1, #2, #3)
  - [x] Nuevo enum `ProvisionInitialAdminResult` en `application/usecase/` (mismo patrón que `RegisterAccountResult`/`ResendVerificationResult`):
    ```java
    package com.auth_service.auth.application.usecase;

    public enum ProvisionInitialAdminResult {
        ADMIN_CREATED,
        ADMIN_ALREADY_EXISTS,
        MISSING_CONFIGURATION
    }
    ```
  - [x] Nuevo caso de uso `application/usecase/ProvisionInitialAdminUseCase.java` — mismo patrón de inyección por constructor y `@Transactional` que `RegisterAccountUseCase`, pero sin `ApplicationEventPublisher` (no hay email ni Evento de Dominio que disparar en esta historia — el Administrador inicial no recibe correo de bienvenida ni existe todavía el outbox de Epic 6):
    ```java
    package com.auth_service.auth.application.usecase;

    import com.auth_service.auth.domain.model.Account;
    import com.auth_service.auth.domain.model.Email;
    import com.auth_service.auth.domain.model.HashedPassword;
    import com.auth_service.auth.domain.model.RawPassword;
    import com.auth_service.auth.domain.model.Role;
    import com.auth_service.auth.domain.port.AccountRepository;
    import com.auth_service.auth.domain.port.PasswordHasher;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    /**
     * FR-12 — aprovisiona la Cuenta ADMIN inicial en el arranque. Invocado
     * exclusivamente por {@code AdminBootstrapRunner} (config/, único llamador
     * permitido — AD-6: la mutación vive aquí, el ApplicationRunner es solo el
     * punto de entrada de infraestructura, igual que un controller delega en
     * un caso de uso en vez de tocar el repositorio directamente).
     */
    @Service
    public class ProvisionInitialAdminUseCase {

        private final AccountRepository accountRepository;
        private final PasswordHasher passwordHasher;

        public ProvisionInitialAdminUseCase(AccountRepository accountRepository, PasswordHasher passwordHasher) {
            this.accountRepository = accountRepository;
            this.passwordHasher = passwordHasher;
        }

        @Transactional
        public ProvisionInitialAdminResult provision(String rawEmail, String rawPassword) {
            if (accountRepository.existsByRole(Role.ADMIN)) {
                return ProvisionInitialAdminResult.ADMIN_ALREADY_EXISTS;
            }
            if (rawEmail == null || rawEmail.isBlank() || rawPassword == null || rawPassword.isBlank()) {
                return ProvisionInitialAdminResult.MISSING_CONFIGURATION;
            }

            Email email = new Email(rawEmail);
            HashedPassword hashedPassword = passwordHasher.hash(new RawPassword(rawPassword));
            Account admin = Account.registerAdmin(email, hashedPassword);
            accountRepository.save(admin);

            return ProvisionInitialAdminResult.ADMIN_CREATED;
        }
    }
    ```
  - [x] **Nota de diseño explícita (no inventar alcance más allá de esto):** si `AUTH_ADMIN_EMAIL`/`AUTH_ADMIN_PASSWORD` están definidos pero con un valor inválido (email mal formado, contraseña que no cumple la política de `RawPassword`), `new Email(...)`/`new RawPassword(...)` lanzan `DomainValidationException` (AD-14, fail-fast) y el arranque falla con esa excepción. Los 3 AC de esta historia no cubren ese caso explícitamente, pero es la consecuencia directa y ya establecida de reutilizar los Value Objects existentes — fallar ruidosamente en un arranque con configuración de administrador inválida es preferible a crear silenciosamente una Cuenta ADMIN rota o a ignorar el error. No se añade manejo especial ni un cuarto resultado de enum para esto: es responsabilidad de quien configura el entorno corregir el valor y reiniciar.
  - [x] Tests nuevos en `ProvisionInitialAdminUseCaseTest` (unit, mocks de `AccountRepository`/`PasswordHasher`, mismo estilo que `LoginUseCaseTest`/`RegisterAccountUseCase`-style tests):
    - `adminAlreadyExistsSkipsCreationAndReturnsAlreadyExists` — `existsByRole(ADMIN)` devuelve `true` → resultado `ADMIN_ALREADY_EXISTS`, `verify(accountRepository, never()).save(any())`, `verify(passwordHasher, never()).hash(any())`.
    - `missingEmailAndPasswordWithNoAdminReturnsMissingConfiguration` — `existsByRole` devuelve `false`, `rawEmail=null`, `rawPassword=null` → `MISSING_CONFIGURATION`, sin `save`.
    - `blankEmailOrPasswordTreatedAsMissingConfiguration` — parametrizado o dos tests: `rawEmail=""`/`rawPassword` válido, y viceversa → `MISSING_CONFIGURATION` en ambos casos.
    - `validConfigurationWithNoAdminCreatesActiveAdminAccount` — `existsByRole` devuelve `false`, email/password válidos → `ADMIN_CREATED`; `verify(accountRepository).save(argThat(a -> a.status() == AccountStatus.ACTIVE && a.roles().containsAll(Set.of(Role.ADMIN, Role.USER))))`.
    - `invalidEmailFormatPropagatesDomainValidationException` — `existsByRole` devuelve `false`, `rawEmail="no-es-un-email"` → `assertThatThrownBy(...).isInstanceOf(DomainValidationException.class)`, sin `save`.
    - `weakPasswordPropagatesDomainValidationException` — mismo patrón con `rawPassword="corta"`.

- [x] Task 5: `AdminBootstrapRunner` — punto de entrada en el arranque (AC: #1, #2, #3)
  - [x] Nuevo `ApplicationRunner` en `config/` (ubicación fijada explícitamente por `ARCHITECTURE-SPINE.md`, tabla "Capability → Architecture Map": *"FR-12 Admin inicial | seeder en `config/` (ApplicationRunner) | AD-6, AD-10"*):
    ```java
    package com.auth_service.auth.config;

    import com.auth_service.auth.application.usecase.ProvisionInitialAdminResult;
    import com.auth_service.auth.application.usecase.ProvisionInitialAdminUseCase;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.boot.ApplicationArguments;
    import org.springframework.boot.ApplicationRunner;
    import org.springframework.stereotype.Component;

    /**
     * FR-12 — dispara el aprovisionamiento del primer Administrador en cada
     * arranque. Es deliberadamente un {@link ApplicationRunner} en {@code
     * config/} y no un caso de uso: no orquesta ninguna mutación por sí mismo
     * (eso vive en {@link ProvisionInitialAdminUseCase}, AD-6) — solo decide
     * qué loggear según el resultado, igual que un controller traduce el
     * resultado de un caso de uso a una respuesta HTTP.
     */
    @Component
    public class AdminBootstrapRunner implements ApplicationRunner {

        private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

        private final ProvisionInitialAdminUseCase provisionInitialAdminUseCase;
        private final AdminBootstrapProperties adminBootstrapProperties;

        public AdminBootstrapRunner(ProvisionInitialAdminUseCase provisionInitialAdminUseCase,
                                     AdminBootstrapProperties adminBootstrapProperties) {
            this.provisionInitialAdminUseCase = provisionInitialAdminUseCase;
            this.adminBootstrapProperties = adminBootstrapProperties;
        }

        @Override
        public void run(ApplicationArguments args) {
            ProvisionInitialAdminResult result = provisionInitialAdminUseCase.provision(
                    adminBootstrapProperties.email(), adminBootstrapProperties.password());

            switch (result) {
                case ADMIN_CREATED ->
                        log.info("Administrador inicial aprovisionado desde AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD.");
                case ADMIN_ALREADY_EXISTS ->
                        log.debug("Ya existe al menos una Cuenta ADMIN — no se aprovisiona ninguna nueva.");
                case MISSING_CONFIGURATION ->
                        log.warn("AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD no están definidos y no existe ninguna " +
                                "Cuenta ADMIN — el servicio arranca sin administrador inicial. Defina ambas " +
                                "variables y reinicie para aprovisionar el primer Administrador.");
            }
        }
    }
    ```
  - [x] No requiere test unitario propio más allá de una comprobación trivial de logging (este proyecto no testea logging de otros componentes similares, p. ej. `EmailNotificationListener` no tiene test unitario de sus mensajes de log) — el comportamiento observable (¿se creó la Cuenta o no?) se cubre por la integración de Task 6.

- [x] Task 6: Test de integración `AdminBootstrapRunnerIntegrationTest` (AC: #1, #2, #3)
  - [x] Nuevo test en `src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerIntegrationTest.java` — mismo patrón Testcontainers que `AuthLoginIntegrationTest`/`GoogleLoginIntegrationTest` (`@SpringBootTest`, `@Testcontainers(disabledWithoutDocker = true)`, `@Container @ServiceConnection PostgreSQLContainer`), pero **sin** `@AutoConfigureMockMvc`/`MockMvc` — esta historia no expone ningún endpoint HTTP, lo que se verifica es que `AdminBootstrapRunner` corrió durante el arranque del `ApplicationContext` y dejó el estado esperado en BD. Tres clases de test separadas (property distinta por clase ⇒ contextos Spring distintos, cacheados independientemente — no se puede parametrizar `@TestPropertySource` en tiempo de ejecución dentro de la misma clase porque el runner ya corrió al arrancar el contexto):
    - `AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest` — `@TestPropertySource(properties = {"auth.jwt.secret-current=...", "auth.oauth2.success-redirect-uri=http://localhost/ok", "auth.oauth2.failure-redirect-uri=http://localhost/fail", "auth.admin.email=admin@example.com", "auth.admin.password=Str0ngAdminPass1"})` (las tres primeras ya son obligatorias para que el contexto levante — mismo bootstrap mínimo que el resto de tests de integración) → tras `@Autowired AccountRepository`, `accountRepository.findByEmail(new Email("admin@example.com"))` presente, `status() == ACTIVE`, `roles()` contiene `ADMIN` y `USER`, `passwordHasher.matches("Str0ngAdminPass1", account.passwordHash())` es `true`.
    - `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest` — mismas properties de `auth.admin.*`, pero antes de que Spring construya el `ApplicationRunner` ya existe una Cuenta `ADMIN` distinta persistida. **Problema de orden a resolver explícitamente durante la implementación:** los `ApplicationRunner` corren automáticamente al levantar el `ApplicationContext`, antes de que el cuerpo de un `@Test` pueda persistir nada — no es posible "pre-sembrar" la Cuenta ADMIN vía `@Autowired AccountRepository` dentro del test como hacen los demás tests de este proyecto (`persistAccount(...)` en `AuthLoginIntegrationTest`). Usar en su lugar un `@TestConfiguration`/`ApplicationRunner` propio con `@Order` anterior al de producción (Spring ejecuta los `ApplicationRunner` beans en orden de declaración salvo `@Order` explícito) que inserte la Cuenta ADMIN antes de que corra `AdminBootstrapRunner`, o alternativamente un `ApplicationListener<ApplicationPreparedEvent>`/`@DynamicPropertySource` con un `Flyway` callback — evaluar la opción más simple que no rompa el resto de la suite; documentar la elección final en el Dev Agent Record. Verificar al final: solo 1 Cuenta con Rol `ADMIN` en BD (`accountRepository.existsByRole(Role.ADMIN)` más un conteo directo si el port no alcanza), y que sigue siendo la preexistente (mismo email), no la de `auth.admin.email`.
    - `AdminBootstrapRunnerWarnsAndStartsWhenConfigurationMissingIntegrationTest` — `auth.admin.email`/`auth.admin.password` no seteados (o vacíos explícitamente, dado el default `${AUTH_ADMIN_EMAIL:}` de `application.properties`) y sin ningún ADMIN preexistente → el `ApplicationContext` arranca sin lanzar excepción (`assertThatCode(() -> context.getBean(AdminBootstrapRunner.class)).doesNotThrowAnyException()` o simplemente que el propio `@SpringBootTest` no falle) y `accountRepository.existsByRole(Role.ADMIN)` es `false` tras el arranque. No se afirma nada sobre el contenido exacto del mensaje de log (frágil); basta con confirmar que no se creó ninguna Cuenta y que el servicio sigue operativo (p. ej. una llamada trivial a otro bean/health del contexto).
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Epics 1, 2 y 3.

### Review Findings

- [x] [Review][Patch] Cuentas solo-federadas (Google/GitHub, sin `passwordHash`) pueden ser bloqueadas vía `POST /auth/login` y no tenían ruta de auto-recuperación — `recordFailedLoginAttempt` solo exigía `status == ACTIVE`, no `passwordHash != null`. **Decisión (2026-07-21): aplicar el fix completo** — `recordFailedLoginAttempt`/`LoginUseCase` ignoran Cuentas sin `passwordHash` local (nada que adivinar, no cuenta como intento fallido) Y `FederatedLoginUseCase`/`RefreshTokenUseCase` invocan `unlockIfExpired` antes de rechazar por status, igual que `LoginUseCase`. Aplicado y verificado (`LoginUseCaseTest`, `FederatedLoginUseCaseTest`, `RefreshTokenUseCaseTest` en verde). [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java, FederatedLoginUseCase.java, RefreshTokenUseCase.java]
- [x] [Review][Defer] La convención de este proyecto de anotar cada test de integración con `@Transactional` a nivel de clase hace que un `@TransactionalEventListener(phase = AFTER_COMMIT)` nunca se dispare durante ningún test (incluido el nuevo `AuthLoginLockoutIntegrationTest`, que por tanto no valida ni el envío del email de bloqueo ni la regresión de `noRollbackFor` que su javadoc dice cubrir) [src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginLockoutIntegrationTest.java] — deferred: es una limitación de convención de toda la suite (no solo de esta historia); documentar y revisar cuando se toque la estrategia de testing de eventos transaccionales en Epic 5/6.
- [x] [Review][Decision] Esta story (4.1) y la Story 3.2 (bloqueo por fuerza bruta, ya `done`) estaban mezcladas sin commitear en el mismo working tree. **Decisión (2026-07-21): un solo commit para ambas** — se commitean juntas tal como está en el working tree; el File List de 4.1 documenta solo su propio alcance (3.2 ya tiene su propio story file separado con su propio File List).
- [x] [Review][Patch] Falta test para la combinación de AC #2 con configuración ausente — ni `ProvisionInitialAdminUseCaseTest.adminAlreadyExistsSkipsCreationAndReturnsAlreadyExists` ni `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest` cubren "ya existe un ADMIN" + `AUTH_ADMIN_EMAIL`/`PASSWORD` no definidos, pese a que el AC lo exige explícitamente ("independientemente de si... están definidos o no"). Añadido `adminAlreadyExistsSkipsCreationAndReturnsAlreadyExistsEvenWithoutConfiguration`. [src/test/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCaseTest.java]
- [x] [Review][Patch] `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest` solo verifica que la Cuenta ADMIN preexistente sigue `isPresent()`, nunca revalida que su `status()`/`roles()`/`passwordHash()` sigan intactos, pese a que el nombre del test y la Task 6 prometen verificar que queda "sin modificar". Reforzado con `hasValueSatisfying` sobre status/roles/password hash. [src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest.java]
- [x] [Review][Patch] El mismo test tampoco confirma que exista exactamente 1 Cuenta ADMIN (solo un booleano `existsByRole`) — Task 6 preveía un conteo directo como fallback si el port no alcanzaba. Añadido conteo vía `AccountJpaRepository` (fallback de test, no toca el port de dominio). [src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest.java]
- [x] [Review][Patch] El bloque `auth.admin.email`/`auth.admin.password` quedó al final de `application.properties`, no justo después de `auth.lockout.*` como especificaba explícitamente la Task 3. Movido. [src/main/resources/application.properties]
- [x] [Review][Patch] `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest` depende de que `AdminBootstrapRunner` no declare `@Order` (tratado implícitamente como `LOWEST_PRECEDENCE`) — esa garantía de orden solo está explicada en el Dev Agent Record, no en la clase de producción. Añadido `@Order(Ordered.LOWEST_PRECEDENCE)` explícito con javadoc. [src/main/java/com/auth_service/auth/config/AdminBootstrapRunner.java]
- [x] [Review][Defer] Carrera de lost-update en `recordFailedLoginAttempt` (intentos fallidos concurrentes) [src/main/java/com/auth_service/auth/domain/model/Account.java:238] — deferred, pre-existing (ya documentado en `deferred-work.md` por esta misma historia)
- [x] [Review][Defer] Carrera en `ProvisionInitialAdminUseCase.provision` (dos arranques concurrentes podrían crear dos ADMIN) [src/main/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCase.java] — deferred, pre-existing (reconocida y diferida explícitamente en Dev Notes de esta historia, mismo criterio que 3.2)
- [x] [Review][Defer] DoS de Cuenta por fuerza bruta de terceros conociendo el email de la víctima [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — deferred, pre-existing (diseño explícito de FR-8/FR-9, ya documentado en `deferred-work.md`)
- [x] [Review][Defer] `save()` redundante en el camino auto-desbloqueo-seguido-de-fallo [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — deferred, pre-existing (ya documentado en `deferred-work.md`, ineficiencia menor)
- [x] [Review][Defer] `AdminBootstrapProperties` (record) expone la contraseña del admin en texto plano vía el `toString()` autogenerado, sin máscara [src/main/java/com/auth_service/auth/config/AdminBootstrapProperties.java] — deferred, pre-existing (mismo patrón sin máscara que `JwtProperties`/`AuthTokenProperties`, no introducido por esta historia)
- [x] [Review][Defer] `LoginUseCaseTest` verifica `argThat` sobre la misma instancia mutable de `Account` en el momento de la verificación, no una foto del momento del `save()` [src/test/java/com/auth_service/auth/application/usecase/LoginUseCaseTest.java] — deferred, pre-existing (higiene de test, no hay mutación posterior que lo explote hoy)

## Dev Notes

- **La ubicación del mecanismo NO es ambigua — está fijada por la arquitectura, no inferida:** `ARCHITECTURE-SPINE.md`, tabla "Capability → Architecture Map", fila `FR-12`: *"Admin inicial | seeder en `config/` (ApplicationRunner) | AD-6, AD-10"*. Esto zanja la pregunta abierta de PRD §9 sobre "seed vs. migración vs. script manual" para esta historia: es un `ApplicationRunner`, no una migración Flyway con datos hardcodeados (rompería AD-7/AD-10 al fijar credenciales en el repositorio) ni un script manual (contradice literalmente la User Story: *"nunca tenga que tocar la base de datos a mano"*).
- **AD-6 ("toda mutación pasa por un caso de uso de application/") sigue aplicando aunque el punto de entrada viva en `config/`:** por eso esta historia separa `AdminBootstrapRunner` (infraestructura, config/, cero lógica de negocio, solo interpreta un enum de resultado — igual que un controller) de `ProvisionInitialAdminUseCase` (`application/usecase/`, `@Transactional`, la única clase que decide si crear la Cuenta y la crea). El mapeo del architecture spine dice "seeder en config/" refiriéndose al punto de entrada/disparador, no a dónde vive la mutación.
- **Por qué no hay Evento de Dominio ni email en esta historia:** el Capability Map no incluye FR-12 en la lista de eventos de `AD-15` (`AccountRegistered`, `AccountVerified`, `AccountLocked`, `AccountDisabled`, `TokenFamilyRevoked` — FR-12 no está ahí), y no hay ningún AC ni FR que pida notificar al propio Administrador por email de su propia creación (tiene sentido: él mismo definió las variables de entorno, ya conoce la contraseña). No se inventa ese comportamiento.
- **Por qué `existsByRole` y no un listado/paginado:** la Story 4.2 (`ManageAccountUseCase`, listado paginado de Cuentas) es la que necesita consultas más ricas sobre `Account`; esta historia solo necesita responder sí/no a "¿existe ya un ADMIN?" — añadir más superficie al port ahora sería scope creep sin AC que lo pida.
- **Idempotencia real de "primer arranque":** como `ProvisionInitialAdminUseCase.provision` vuelve a evaluarse en *cada* arranque (no solo el primero — no existe ni se necesita ninguna marca de "ya se ejecutó una vez"), la comprobación `existsByRole(Role.ADMIN)` es la que garantiza que arranques subsiguientes con las mismas variables de entorno definidas no dupliquen ni reseteen al Administrador (AC #2). Esto es más simple y más robusto que una bandera de "ya corrí" (que podría desincronizarse de la realidad de la BD).
- **Por qué el email/password inválidos hacen fallar el arranque (fail-fast) en vez de solo loggear una advertencia:** `Email`/`RawPassword` son Value Objects (AD-14) que ya validan sus invariantes en construcción en todo el resto del sistema — reutilizarlos aquí y dejar que su excepción se propague es consistente con el resto del código (nadie en este proyecto atrapa `DomainValidationException` para convertirla en un warning silencioso). Es una decisión de diseño explícita de esta historia, no una laguna: AUTH_ADMIN_EMAIL/PASSWORD mal configurados son un error de operador que debe ser ruidoso, no una Cuenta ADMIN corrupta silenciosa.
- **Sin cambio de esquema:** `accounts`/`account_roles` (`V1__init.sql`, `V2__accounts.sql`) ya admiten cualquier valor de `Role` en texto libre — no hay `CHECK constraint` que restrinja a `USER`; `Role.ADMIN` ya existe como valor del enum de dominio desde el inicio del proyecto (`Role.java`), simplemente ningún caso de uso lo asignaba todavía. No hace falta migración nueva.
- **Riesgo de concurrencia reconocido y explícitamente diferido (mismo patrón que la Story 3.2 con `recordFailedLoginAttempt`):** si el servicio arrancara con múltiples instancias en paralelo apuntando a la misma BD (no es el caso de este proyecto en `dev`/single-instance, y `NFR-8`/Docker Compose no lo contempla), dos `ApplicationRunner` podrían leer `existsByRole(ADMIN) == false` simultáneamente antes de que cualquiera persista, creando dos Administradores. No se añade bloqueo optimista/pesimista para esto en esta historia — mismo criterio de "riesgo de baja probabilidad, sin infraestructura de multi-instancia todavía" que Story 3.2 usó para diferir el equivalente en `LoginUseCase`. Si se necesita en el futuro, un `unique index` parcial sobre `account_roles` filtrado por `role = 'ADMIN'` sería la mitigación más simple a nivel de BD.
- **Convención de test de integración nueva para este proyecto:** hasta esta historia, todos los tests de integración (`infrastructure/controller/*IntegrationTest`) verificaban comportamiento vía `MockMvc` contra un endpoint HTTP. Esta historia no tiene endpoint — el "input" es la configuración de arranque y el "output" es el estado de BD tras levantar el `ApplicationContext`. Es correcto y suficiente omitir `@AutoConfigureMockMvc`/`MockMvc` aquí; no inventar un endpoint solo para poder testear con el patrón habitual.

### Project Structure Notes

- **Nuevos:**
  - `src/main/java/com/auth_service/auth/config/AdminBootstrapProperties.java`
  - `src/main/java/com/auth_service/auth/config/AdminBootstrapRunner.java`
  - `src/main/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCase.java`
  - `src/main/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminResult.java`
  - `src/test/java/com/auth_service/auth/config/AdminBootstrapPropertiesTest.java`
  - `src/test/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCaseTest.java`
  - `src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerIntegrationTest.java` (o 3 clases separadas, ver Task 6)
- **Modificados:**
  - `src/main/java/com/auth_service/auth/domain/model/Account.java` (+`registerAdmin`)
  - `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java` (+`existsByRole`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountJpaRepository.java` (+`existsByRolesContaining`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java` (+implementación de `existsByRole`)
  - `src/main/resources/application.properties` (+`auth.admin.email`, `auth.admin.password`)
  - `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+`registerAdminCreatesActiveAccountWithAdminAndUserRoles`)
- **Sin cambios:** `AuthController`, `UserController`, `SecurityConfig`, `AuthServiceApplication` (el `ApplicationRunner` se registra solo por ser `@Component`, no requiere tocar la clase principal — mismo mecanismo que `@ConfigurationPropertiesScan` ya usa para las properties existentes), `GlobalExceptionHandler`, cualquier DTO de `infrastructure/controller/dto/` (no hay endpoint nuevo), esquema de BD.
- **Nueva migración:** ninguna — `Role.ADMIN` y las tablas `accounts`/`account_roles` ya existen sin restricción que lo impida.

### References

- [Source: docs/planning-artifacts/epics.md#Epic-4] — Story 4.1 completa (user story + AC Given/When/Then), FR-12
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-12] — "Aprovisionamiento del primer Administrador", `[ASSUMPTION]` de variables de entorno
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#NFR-7] — configuración por entorno, aplica a FR-12
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#Índice-de-Assumptions] — "§4.6 FR-12 — Admin inicial por variables de entorno en primer arranque"
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Capability→Architecture-Map] — "FR-12 Admin inicial | seeder en `config/` (ApplicationRunner) | AD-6, AD-10" — fija el mecanismo, no es una elección de esta historia
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-6] — mutación de estado solo desde `application/usecase`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-10] — configuración por entorno, propiedades tipadas vía `@ConfigurationProperties`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-14] — Value Objects validan invariantes en construcción (fail-fast) — aplica a `Email`/`RawPassword` reutilizados aquí
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Consistency-Conventions] — `Role { USER, ADMIN }` ya definido
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md] — `AUTH_ADMIN_EMAIL`, `AUTH_ADMIN_PASSWORD` listadas como variables de entorno nuevas por AD-10
- [Source: src/main/java/com/auth_service/auth/domain/model/Account.java] — `register`/`registerFederated` como patrón exacto a replicar para `registerAdmin`
- [Source: src/main/java/com/auth_service/auth/domain/model/Role.java] — `ADMIN` ya existe en el enum, sin uso previo
- [Source: src/main/java/com/auth_service/auth/domain/port/AccountRepository.java] — port actual (`save`, `findByEmail`, `findById`), se añade `existsByRole`
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountJpaRepository.java] — solo tenía `findByEmail`; patrón de query derivada de Spring Data a replicar
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java] — patrón de delegación adapter→jpaRepository
- [Source: src/main/java/com/auth_service/auth/config/AuthTokenProperties.java] y [Source: src/main/java/com/auth_service/auth/config/JwtProperties.java] — patrón `record` + `@ConfigurationProperties` + constructor compacto (con validación); `AdminBootstrapProperties` difiere deliberadamente al no validar, ver Dev Notes
- [Source: src/main/java/com/auth_service/auth/application/usecase/RegisterAccountUseCase.java] — patrón de caso de uso `@Transactional` con resultado enum (`RegisterAccountResult`), replicado por `ProvisionInitialAdminUseCase`/`ProvisionInitialAdminResult`
- [Source: src/main/java/com/auth_service/auth/AuthServiceApplication.java] — `@ConfigurationPropertiesScan` ya registra cualquier `@ConfigurationProperties` nuevo automáticamente, sin tocar esta clase; `@Component` de `AdminBootstrapRunner` se registra igual sin tocarla
- [Source: src/main/resources/application.properties] — bloques `auth.token.*`/`auth.lockout.*`/`auth.jwt.*` como patrón exacto de sección nueva, incluyendo el patrón `${VAR:}` para valor opcional con default vacío (`auth.jwt.secret-previous`)
- [Source: src/main/resources/db/migration/V2__accounts.sql] — `accounts`/`account_roles` sin restricción de valores de `role`, confirma que no hace falta migración
- [Source: src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginIntegrationTest.java] y [Source: src/test/java/com/auth_service/auth/infrastructure/controller/GoogleLoginIntegrationTest.java] — patrón Testcontainers/`@TestPropertySource` a replicar (sin `MockMvc`, ver Dev Notes) para `AdminBootstrapRunnerIntegrationTest`
- [Source: src/test/java/com/auth_service/auth/application/usecase/LoginUseCaseTest.java] — patrón de test unitario con mocks de ports a replicar para `ProvisionInitialAdminUseCaseTest`
- [Source: src/test/java/com/auth_service/auth/domain/model/AccountTest.java] — patrón de test de dominio a replicar para `registerAdminCreatesActiveAccountWithAdminAndUserRoles`
- [Source: docs/implementation-artifacts/3-2-bloqueo-automatico-por-fuerza-bruta.md] — plantilla de formato/nivel de detalle de esta historia; patrón de "riesgo de concurrencia diferido" replicado en Dev Notes
- [Source: docs/implementation-artifacts/sprint-status.yaml] — Epic 4 en `backlog` antes de esta historia; Stories 1.1–3.2 todas `done`, ninguna otra dependencia pendiente para arrancar Epic 4

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -Dtest=AccountTest test` — 21/21 verde tras añadir `registerAdmin` (Task 1).
- `./mvnw -q compile` — verde tras Task 2 (`existsByRole` en `AccountRepository`/`AccountJpaRepository`/`AccountRepositoryAdapter`).
- `./mvnw -Dtest=AdminBootstrapPropertiesTest test` — 2/2 verde (Task 3).
- `./mvnw -Dtest=ProvisionInitialAdminUseCaseTest test` — 7/7 verde (Task 4).
- `./mvnw -q compile` — verde tras Task 5 (`AdminBootstrapRunner`).
- `./mvnw -Dtest='AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest,AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest,AdminBootstrapRunnerWarnsAndStartsWhenConfigurationMissingIntegrationTest' test` (con Docker disponible y variables de entorno de `.env.example` exportadas — `DB_HOST/PORT/NAME/USER/PASSWORD`, `JWT_SECRET_CURRENT`, `GOOGLE_CLIENT_ID/SECRET`, `GITHUB_CLIENT_ID/SECRET`, `OAUTH2_SUCCESS/FAILURE_REDIRECT_URI`, `MAIL_HOST/PORT`, `KAFKA_BOOTSTRAP_SERVERS`, `APP_BASE_URL`) — 3/3 verde (Task 6). Primer intento con `webEnvironment = NONE` falló (`NoSuchBeanDefinitionException: ClientRegistrationRepository` — `OAuth2ClientAutoConfiguration` no registra ese bean cuando `spring.main.web-application-type=none`, pero `SecurityConfig.securityFilterChain` sí lo exige incondicionalmente); se cambió a `webEnvironment = RANDOM_PORT` (mismo patrón que el resto de tests de integración del proyecto) y los 3 contextos cargaron correctamente.
- `./mvnw -B verify` (suite completa, Docker disponible, mismas variables de entorno de `.env.example` exportadas) — **277/277 tests verdes, `BUILD SUCCESS`**, incluyendo `ArchitectureRulesTest` (fitness functions de AD-6/hexagonal) y todos los `*IntegrationTest` de Epics 1, 2 y 3 (`AuthLoginIntegrationTest`, `AuthLoginLockoutIntegrationTest`, `GoogleLoginIntegrationTest`, `GitHubLoginIntegrationTest`, `AuthForgotPasswordIntegrationTest`, `AuthResetPasswordIntegrationTest`, etc.) sin regresión.

### Completion Notes List

- Las 3 AC están implementadas y verificadas: (1) sin ninguna Cuenta `ADMIN` y con `AUTH_ADMIN_EMAIL`/`AUTH_ADMIN_PASSWORD` definidos, el arranque crea una Cuenta `ACTIVE` con Roles `ADMIN`+`USER` y contraseña BCrypt — cubierto por `Account.registerAdmin`, `ProvisionInitialAdminUseCase.provision` (resultado `ADMIN_CREATED`) y `AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest`; (2) si ya existe al menos un `ADMIN`, el arranque no crea ni modifica ninguna Cuenta, independientemente de la configuración — cubierto por `existsByRole(ADMIN)` como guarda de entrada en `ProvisionInitialAdminUseCase` (resultado `ADMIN_ALREADY_EXISTS`) y `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest`; (3) sin configuración y sin `ADMIN` existente, se registra un `log.warn` claro y el arranque no falla — cubierto por el resultado `MISSING_CONFIGURATION` en `AdminBootstrapRunner` y `AdminBootstrapRunnerWarnsAndStartsWhenConfigurationMissingIntegrationTest`.
- **Decisión de diseño de Task 6 (orden de `ApplicationRunner` en el test de "ADMIN ya existe"):** de las opciones sugeridas por la story, se eligió un `@TestConfiguration` anidado dentro de `AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest` con un segundo `ApplicationRunner` (bean `seedExistingAdminRunner`) anotado `@Order(Ordered.HIGHEST_PRECEDENCE)`. Spring Boot ejecuta los beans `ApplicationRunner` en orden ascendente de `@Order`; `AdminBootstrapRunner` de producción no declara ningún orden explícito, por lo que `AnnotationAwareOrderComparator` lo trata como `LOWEST_PRECEDENCE` en la comparación. Esto garantiza determinísticamente que el runner de siembra persista la Cuenta ADMIN preexistente (`existing-admin@example.com`) antes de que `AdminBootstrapRunner` evalúe `existsByRole(ADMIN)`. Se descartó la alternativa de `ApplicationListener<ApplicationPreparedEvent>`/callback de Flyway por ser más indirecta y menos legible que un segundo `ApplicationRunner` explícito con `@Order`, que replica el mismo mecanismo (`ApplicationRunner`) que ya usa la clase bajo prueba — más fácil de razonar y de mantener. El test verifica que `existsByRole(ADMIN)` es `true`, que no existe ninguna Cuenta con el email de `auth.admin.email` (no se creó una nueva), y que la Cuenta preexistente sigue presente sin modificar.
- Task 6 usa `webEnvironment = RANDOM_PORT` en las 3 clases de integración (no `NONE` como se consideró inicialmente) — con `NONE`, `OAuth2ClientAutoConfiguration` no registra el bean `ClientRegistrationRepository` (condicional a `web-application-type=servlet`), pero `SecurityConfig.securityFilterChain` lo exige incondicionalmente, causando `NoSuchBeanDefinitionException` al arrancar el contexto. `RANDOM_PORT` es además el patrón ya usado por el resto de tests de integración del proyecto.
- Ningún ajuste de diseño respecto al código de la story: los 6 snippets (factory de dominio, port+adapter, `AdminBootstrapProperties`, `ProvisionInitialAdminUseCase`/`ProvisionInitialAdminResult`, `AdminBootstrapRunner`) se implementaron tal cual estaban especificados.
- No hizo falta ninguna migración nueva ni cambios en `SecurityConfig`/`AuthController`/`UserController`/`GlobalExceptionHandler`/`AuthServiceApplication`, confirmando las notas de "Project Structure Notes" de la story.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/config/AdminBootstrapProperties.java`
- `src/main/java/com/auth_service/auth/config/AdminBootstrapRunner.java`
- `src/main/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCase.java`
- `src/main/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminResult.java`
- `src/test/java/com/auth_service/auth/config/AdminBootstrapPropertiesTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/ProvisionInitialAdminUseCaseTest.java`
- `src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest.java`
- `src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest.java`
- `src/test/java/com/auth_service/auth/config/AdminBootstrapRunnerWarnsAndStartsWhenConfigurationMissingIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (+`registerAdmin`)
- `src/main/java/com/auth_service/auth/domain/port/AccountRepository.java` (+`existsByRole`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountJpaRepository.java` (+`existsByRolesContaining`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java` (+implementación de `existsByRole`)
- `src/main/resources/application.properties` (+`auth.admin.email`, `auth.admin.password`)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (+`registerAdminCreatesActiveAccountWithAdminAndUserRoles`)
