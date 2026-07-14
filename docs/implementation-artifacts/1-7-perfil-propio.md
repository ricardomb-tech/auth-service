---
baseline_commit: 704433e6343a0b75344cee030bccb3988c781ff4
---

# Story 1.7: Perfil propio

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a titular autenticado,
I want consultar mis propios datos,
so that las aplicaciones muestren mi identidad, roles y estado. (FR-10)

## Acceptance Criteria

1. Con un Access Token válido en `Authorization: Bearer`, `GET /api/v1/users/me` es autenticado por `JwtAuthenticationFilter` (ya existente, Story 1.4 — sin cambios) y `GetOwnProfileUseCase` responde `200 OK` con el email, Roles, estado (`AccountStatus`), Identidades Federadas y fecha de creación **de la propia Cuenta del token, nunca de otra** — el `accountId` se obtiene exclusivamente del claim `sub` del token autenticado, jamás de un parámetro de la petición (AD-18).
2. Sin token, con un token inválido o expirado, `GET /api/v1/users/me` responde `401 application/problem+json` — garantizado por el deny-all existente (AD-11): esta ruta **no** se añade a `PUBLIC_ENDPOINTS`, y ya hay un test de la Story 1.1 (`SecurityConfigTest.unauthenticatedRequestToNonPublicRouteReturns401ProblemJson`) que usa exactamente esta ruta como ejemplo — no debe empezar a fallar.
3. Un Access Token válido cuyo claim `sub` no corresponde a ninguna Cuenta existente (caso límite, hoy inalcanzable en la práctica — ver Dev Notes) responde `401 application/problem+json`, nunca `500`.

## Tasks / Subtasks

- [x] Task 1: `AccountNotFoundException` (`domain/exception`) (AC: #3)
  - [x] Clase Java pura (sin Spring, AD-1), mismo patrón exacto que `InvalidRefreshTokenException`: `public class AccountNotFoundException extends RuntimeException { public AccountNotFoundException(String message) { super(message); } }`.
  - [x] **No** reutilizar `AuthenticationFailedException` para este caso: su mensaje en `GlobalExceptionHandler` está fijado a `"Email o contraseña incorrectos."`, específico del flujo de login (Story 1.4) — reusarlo aquí produciría un mensaje semánticamente incorrecto para un token de perfil rechazado.

- [x] Task 2: `GetOwnProfileCommand` + `GetOwnProfileUseCase` (`application/usecase`) (AD-13) (AC: #1, #3)
  - [x] `record GetOwnProfileCommand(String accountId)` — lleva el claim `sub` **crudo** (String), igual que `RefreshCommand`/`LogoutCommand` llevan el token crudo; el parseo a Value Object ocurre dentro del caso de uso, nunca en el controller.
  - [x] `GetOwnProfileUseCase`, `@Service`, `@Transactional(readOnly = true)` (es una consulta, no una mutación — a diferencia de todos los casos de uso anteriores de este Epic, que sí mutan estado). Constructor-injected con **solo** `AccountRepository` — no `TokenIssuer`, no `Clock`: esta historia no emite tokens ni depende de tiempo.
  - [x] Método `Account getOwnProfile(GetOwnProfileCommand command)`:
    1. Parsear `command.accountId()` a `UUID` con `UUID.fromString(...)`; si lanza `IllegalArgumentException` (claim `sub` corrupto/no-UUID), capturar y relanzar `AccountNotFoundException` — no dejar escapar la excepción cruda de `UUID.fromString` (terminaría en el catch-all de `GlobalExceptionHandler` como `500`, violando AC #3).
    2. `accountRepository.findById(new AccountId(uuid))` — si vacío, lanzar `AccountNotFoundException` (AC #3).
    3. Devolver el `Account` encontrado tal cual — **no** se envuelve en un DTO/Result propio del caso de uso; el controller mapea `Account` → `ProfileResponse` directamente (ver Task 3). No hay precedente en este proyecto de un caso de uso de solo-lectura que necesite un wrapper adicional, y crear uno aquí sería una abstracción no pedida por ningún AC.
  - [x] Tests unitarios con mocks de `AccountRepository`: `accountId` válido con Cuenta existente → devuelve el `Account` esperado, `findById` invocado con el `AccountId` correcto; `accountId` válido pero sin Cuenta (`findById` vacío) → lanza `AccountNotFoundException`; `accountId` no-UUID (string arbitrario) → lanza `AccountNotFoundException` sin propagar `IllegalArgumentException`.

- [x] Task 3: DTO + Controller (`infrastructure`) (AC: #1)
  - [x] `infrastructure/controller/dto/ProfileResponse.java` — `record ProfileResponse(String id, String email, Set<String> roles, String status, List<String> federatedIdentities, Instant createdAt)`.
    - `federatedIdentities` es **siempre una lista vacía en esta historia** — Epic 2 (Story 2.1, todavía `backlog`) es quien introduce la tabla `federated_identities` y el concepto de Identidad Federada real. El campo se incluye ahora (no se difiere) porque FR-10/AC #1 lo exige explícitamente en la respuesta y porque añadirlo hoy evita un cambio de forma de la respuesta cuando llegue Epic 2 (AD-18, versionado sin ruptura) — pero no hay ninguna consulta real que hacer todavía; hardcodear `List.of()` es la implementación correcta, no un placeholder pendiente.
    - `id` se incluye aunque el AC original solo dice "email, Roles, estado, Identidades Federadas y fechas": ya es visible para el cliente como claim `sub` del propio token, así que no es una exposición nueva, y es una práctica estándar de REST incluirlo en un recurso `/me`. Si se prefiere seguir el AC al pie de la letra, este campo es el único candidato a recortar — no bloqueante.
    - Solo `createdAt` cubre "fechas" (plural en el epic) — `Account` no tiene hoy ningún otro campo de fecha (no hay `updatedAt`/`lastLoginAt` en el modelo ni en el esquema `V2__accounts.sql`); no se agrega ninguno nuevo, sería scope creep sin AC ni migración que lo respalde.
  - [x] `UserController` (NUEVO): `@RestController @RequestMapping("/api/v1/users")`, constructor-injected con `GetOwnProfileUseCase`.
    - `@GetMapping("/me") public ResponseEntity<ProfileResponse> me(Authentication authentication)` — Spring MVC resuelve el parámetro `Authentication` automáticamente desde `HttpServletRequest.getUserPrincipal()` (que `JwtAuthenticationFilter`, Story 1.4, ya puebla vía `SecurityContextHolder`); **no** se necesita `@AuthenticationPrincipal` ni un `UserDetailsService` — el principal de `UsernamePasswordAuthenticationToken` ya es el `String` del claim `sub` (`authentication.getName()` lo devuelve directo).
    - Body: `Account account = getOwnProfileUseCase.getOwnProfile(new GetOwnProfileCommand(authentication.getName())); return ResponseEntity.ok(toResponse(account));` con un método privado `toResponse` que mapea `Account` → `ProfileResponse` (roles: `account.roles().stream().map(Enum::name).collect(toSet())`; status: `account.status().name()`; `federatedIdentities`: `List.of()`).
    - **No** try/catch: `AccountNotFoundException` se deja propagar a `GlobalExceptionHandler` (mismo patrón que `AuthenticationFailedException`/`InvalidRefreshTokenException` en `AuthController`).
  - [x] `GlobalExceptionHandler` (UPDATE): añadir `@ExceptionHandler(AccountNotFoundException.class)` → `ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required.")` — mensaje idéntico al de `SecurityConfig.authenticationEntryPoint()` (Story 1.1), para que un token con `sub` inválido sea indistinguible de un token ausente desde la perspectiva del cliente.
  - [x] `UserControllerTest` (NUEVO): `@WebMvcTest(controllers = UserController.class)`, `@Import({SecurityConfig.class, JwtAuthenticationFilter.class})`, `@EnableConfigurationProperties(JwtProperties.class)`, mismo `@TestPropertySource` de secreto de test que `AuthControllerTest`/`SecurityConfigTest` — sin el `@Import`, el slice cae al deny-all por defecto de Spring Boot (mismo comentario ya documentado en `AuthControllerTest`). `@MockitoBean GetOwnProfileUseCase`. Construir un JWT real firmado (mismo helper `validToken()` que `SecurityConfigTest`, con `subject(accountId.toString())`) para los casos autenticados. Tests: token válido + `getOwnProfileUseCase` mockeado devolviendo un `Account` de prueba → `200`, JSON con los campos esperados (`jsonPath` sobre `email`, `roles`, `status`, `federatedIdentities` vacío), `getOwnProfileUseCase.getOwnProfile(eq(new GetOwnProfileCommand(accountId.toString())))` invocado exactamente una vez; sin header `Authorization` → `401 application/problem+json`, `getOwnProfileUseCase` nunca invocado; token válido pero `getOwnProfileUseCase` lanza `AccountNotFoundException` → `401 application/problem+json` (no `500`).

- [x] Task 4: Test de integración (AC: #1, #2, #3)
  - [x] `UserProfileIntegrationTest` — `@SpringBootTest` + Testcontainers + `@Transactional`, mismo patrón exacto que `AuthLoginIntegrationTest`/`AuthLogoutIntegrationTest`: Cuenta persistida directamente vía `AccountRepository`/`PasswordHasher` reales, Access Token obtenido vía `POST /auth/login` real (no construido a mano) para ejercitar la cadena completa `JwtAuthenticationFilter` → `UserController` → `GetOwnProfileUseCase` → `AccountRepository` contra PostgreSQL real.
  - [x] Casos: `GET /api/v1/users/me` con Access Token de una Cuenta `ACTIVE` real → `200`, cuerpo con el email/roles/status reales de esa Cuenta (verificado leyendo los valores devueltos, no solo el código de estado); sin header `Authorization` → `401 application/problem+json`; con un token manifiestamente inválido (string arbitrario) → `401 application/problem+json`; suite completa (`./mvnw -B verify`) sin regresión en Stories 1.1-1.6.
  - [x] **No** se necesita un caso de "Cuenta borrada tras emitir el token" en integración (AC #3) — no existe ningún flujo de borrado de Cuenta en el sistema hoy (ni siquiera Epic 4 lo introduce; desactivar ≠ borrar), así que ese camino solo es alcanzable a nivel unitario mockeando `AccountRepository` (ya cubierto en Task 2). Añadir infraestructura de borrado real solo para probar este AC sería scope creep no pedido.

### Review Findings

<!-- Code review 2026-07-13 — capas: Blind Hunter, Edge Case Hunter, Acceptance Auditor. 2 decision-needed (resueltas), 10 patch (aplicados), 5 defer, 2 dismissed. -->

- [x] [Review][Decision] Cuenta no-ACTIVE con Access Token vigente recibe `200` con su perfil — **Resuelto: intencional** (AD-3, token stateless; la respuesta incluye `status` para que el cliente lo refleje). Documentado en Dev Notes y en el Javadoc de `GetOwnProfileUseCase`; comportamiento fijado por el nuevo test `nonActiveAccountIsReturnedAsIsWithItsStatus`. (edge)
- [x] [Review][Decision] La suite de integración nunca se ejecutó (`UserProfileIntegrationTest` 3/3 `skipped`, Docker no disponible en la sesión de dev) — **Resuelto: se ejecutó `./mvnw -B verify` con Docker durante el code review** (ver resultado en el resumen del review). (auditor)
- [x] [Review][Patch] Sin logging en los caminos 401 de seguridad nuevos — aplicado: mensajes de excepción distintivos en `GetOwnProfileUseCase` (sub malformado vs Cuenta inexistente) y `log.warn` del motivo real en `handleAccountNotFoundException` [GetOwnProfileUseCase.java:37, GlobalExceptionHandler.java:73] (blind)
- [x] [Review][Patch] NPE → 500 con `authentication == null` (controller) o `command.accountId() == null` (use case) — aplicado: guard de null en `UserController.me` y `catch (IllegalArgumentException | NullPointerException)` en el use case, con test unitario del caso null [UserController.java:34, GetOwnProfileUseCase.java:41] (blind+edge)
- [x] [Review][Patch] Acoplamiento semántico `AccountNotFoundException` → 401 global — aplicado: Javadoc de la excepción advierte que flujos futuros de búsqueda de Cuenta ajena (Epic 4 `GET /users/{id}`) no deben reutilizarla (necesitan 404 y excepción propia) [AccountNotFoundException.java] (blind+edge)
- [x] [Review][Patch] El test del AC #3 no verificaba la indistinguibilidad — aplicado: aserción `detail == "Authentication required."` en el test del AC #3 y en los caminos de token ausente/expirado/inválido (slice + integración) [UserControllerTest.java, UserProfileIntegrationTest.java] (blind)
- [x] [Review][Patch] Falta test de token expirado contra `/api/v1/users/me` — aplicado: `expiredTokenReturns401ProblemJsonAndNeverInvokesUseCase` con token bien firmado y vencido [UserControllerTest.java] (edge+auditor)
- [x] [Review][Patch] `id` y `createdAt` sin aserciones — aplicado: aserciones exactas en el slice test (`id` = UUID de la Cuenta, `createdAt` ISO-8601) y de presencia en integración [UserControllerTest.java, UserProfileIntegrationTest.java] (edge+auditor)
- [x] [Review][Patch] Aserciones `roles[0]` frágiles — aplicado: `jsonPath("$.roles", containsInAnyOrder(...))` en slice e integración [UserControllerTest.java, UserProfileIntegrationTest.java] (edge)
- [x] [Review][Patch] Javadoc de `UserProfileIntegrationTest` sobredeclaraba cobertura — aplicado: aclara que cubre AC #1 y #2, y por qué el AC #3 solo es alcanzable a nivel unitario/slice [UserProfileIntegrationTest.java] (blind)
- [x] [Review][Patch] Test unitario redundante `randomUuidWithoutMatchingAccountThrowsAccountNotFoundException` — aplicado: eliminado; en su lugar, tests de `sub == null` y de Cuenta no-ACTIVE [GetOwnProfileUseCaseTest.java] (blind)
- [x] [Review][Defer] Handlers de Stories 1.4/1.5 (`AuthenticationFailedException`, `InvalidRefreshTokenException`) tragan la causa real sin loguearla — incluye "reuso detectado" (AD-8), evento de seguridad de primer orden [GlobalExceptionHandler.java:40-52] — deferred, pertenece a los grupos de revisión pendientes 1-4/1-5
- [x] [Review][Defer] Idiomas mezclados en el contrato de error del API (`"Email o contraseña incorrectos."` vs `"Authentication required."`) — deferred, atraviesa Stories 1.1/1.4/1.5; unificar requiere decisión de producto sin romper la indistinguibilidad con el entry point
- [x] [Review][Defer] `@SpringBootTest(RANDOM_PORT)` + `MockMvc`: se arranca un servlet container real que ninguna petición usa [UserProfileIntegrationTest.java:49] — deferred, patrón heredado de los tests de integración de 1.4-1.6; corregir uniformemente
- [x] [Review][Defer] Respuestas 401 sin cabecera `WWW-Authenticate` (RFC 9110 §15.5.2) [GlobalExceptionHandler.java] — deferred, omisión preexistente del entry point de Story 1.1; corregir de forma transversal
- [x] [Review][Defer] Secreto de test duplicado como literal (property + constante + tercer archivo) con sufijo engañoso `-32bytes` (son 51 bytes) [UserControllerTest.java:52] — deferred, patrón preexistente en `AuthControllerTest`/`SecurityConfigTest`

## Dev Notes

- **Por qué el `accountId` nunca viaja como parámetro de la petición:** el propio AD-18 y el AC #1 son explícitos — "nunca de otra [Cuenta]". La única fuente de verdad del `accountId` es el claim `sub` del token ya autenticado por `JwtAuthenticationFilter` (Story 1.4), expuesto vía `Authentication.getName()`. Un `GET /api/v1/users/{id}` con un `id` arbitrario en la URL sería una vulnerabilidad de IDOR — no es lo que pide esta historia (eso es gestión administrativa, Epic 4, con `@PreAuthorize("hasRole('ADMIN')")`).
- **Caso "Cuenta no encontrada para un `sub` válido" es de baja probabilidad hoy:** `TokenIssuer` (único emisor de tokens, AD-2) solo firma un `sub` que corresponde a un `Account` recién leído de `AccountRepository` en el momento de emitir — no hay ningún flujo de borrado de Cuenta en el sistema (desactivar, Epic 4, no es borrar). El único camino realista hacia AC #3 es un Access Token todavía vigente (TTL 15 min) para una Cuenta borrada manualmente en la base de datos fuera de la API, o un claim `sub` corrupto por un bug de firma. Se implementa de todos modos porque es la diferencia entre un `401` correcto y un `500` que expondría un stack trace en un caso límite real, y el costo de implementarlo es mínimo (un `Optional.orElseThrow`).
- **`GetOwnProfileUseCase` es de solo lectura (`@Transactional(readOnly = true)`)** — primer caso de uso de este Epic que no muta estado; no necesita `noRollbackFor` ni ninguna de las consideraciones transaccionales de `LoginUseCase`/`RefreshTokenUseCase`/`LogoutUseCase`.
- **`federatedIdentities` vacío no es un TODO:** es el comportamiento correcto mientras Epic 2 no exista. No crear la tabla `federated_identities` ni ningún port/adapter para ella en esta historia — eso es explícitamente Story 2.1 (`backlog`), fuera de alcance.
- **Cuenta no-ACTIVE con Access Token vigente ve su propio perfil (decisión de code review, 2026-07-13):** una Cuenta `LOCKED`/`DISABLED`/`PENDING_VERIFICATION` cuyo Access Token (TTL 15 min) sigue vigente recibe `200` con su perfil en `/me` — intencional bajo AD-3 (token stateless): la respuesta incluye `status` precisamente para que el cliente lo refleje, y el corte real de acceso ocurre en el refresh (Story 1.5, valida la Cuenta al rotar). Comportamiento fijado por `GetOwnProfileUseCaseTest.nonActiveAccountIsReturnedAsIsWithItsStatus` y documentado en el Javadoc de `GetOwnProfileUseCase`.
- **Riesgos heredados que NO aplican a esta historia:** el canal lateral de timing ya diferido en Stories 1.2/1.3/1.4/1.6 no aplica aquí — no hay comparación de hash ni rama con/sin escritura a BD condicionada por datos de usuario; ambos caminos (Cuenta encontrada/no encontrada) hacen exactamente una lectura. No se agrega una entrada nueva a `deferred-work.md` por esta historia salvo que el code review encuentre algo específico.

### Project Structure Notes

- **Sin migración Flyway nueva** — reutiliza `accounts`/`account_roles` (V2, Story 1.2) vía `AccountRepository.findById` (ya existente desde Story 1.2, ya usado por `VerifyAccountUseCase` y `RefreshTokenUseCase`). No se crea `federated_identities` (ver Dev Notes).
- **Sin cambios en `SecurityConfig`** — `/api/v1/**` nunca estuvo en `PUBLIC_ENDPOINTS`; el deny-all (AD-11) ya protege esta ruta desde la Story 1.1, y `SecurityConfigTest.unauthenticatedRequestToNonPublicRouteReturns401ProblemJson` ya usa `/api/v1/users/me` como ejemplo — confirmar que ese test sigue en verde tras esta historia, no que necesita cambiar.
- **Sin cambios en `JwtAuthenticationFilter`** — ya puebla `SecurityContextHolder` con el claim `sub` como principal desde la Story 1.4; esta historia solo lo consume.
- **Nuevos:** `GetOwnProfileCommand`, `GetOwnProfileUseCase`, `AccountNotFoundException`, `ProfileResponse`, `UserController`, y sus tests (`GetOwnProfileUseCaseTest`, `UserControllerTest`, `UserProfileIntegrationTest`).
- **Modificado:** `GlobalExceptionHandler` (nuevo `@ExceptionHandler(AccountNotFoundException.class)`).

### References

- [Source: docs/planning-artifacts/epics.md#Story-1.7] — historia de usuario y AC original (Given/When/Then)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-10] — "Petición con Access Token válido → datos de la Cuenta del token, nunca de otra"
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-18] — recursos protegidos bajo `/api/v1/**`; FR-10 vive en `UserController` → `GetOwnProfileUseCase`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-3] — Access Token stateless, claim `sub` = UUID de la Cuenta
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-11] — deny-all por defecto, endpoints públicos listados explícitamente
- [Source: docs/implementation-artifacts/1-6-logout.md] — patrón de Command crudo + parseo dentro del caso de uso, patrón de test de integración con Testcontainers
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/security/JwtAuthenticationFilter.java] — puebla `SecurityContextHolder` con `UsernamePasswordAuthenticationToken(subject, null, authorities)`; `authentication.getName()` devuelve el claim `sub` crudo
- [Source: src/test/java/com/auth_service/auth/config/SecurityConfigTest.java] — ya usa `/api/v1/users/me` como ejemplo de ruta protegida no listada como pública (test preexistente, no crear uno duplicado)
- [Source: src/main/java/com/auth_service/auth/domain/port/AccountRepository.java] — `findById(AccountId)` ya existente, sin cambios necesarios

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -q -o test -Dtest=GetOwnProfileUseCaseTest` — 4/4 en verde (Task 2, mocks, sin Docker). Test creado primero y confirmado en rojo (fallo de compilación por `GetOwnProfileUseCase` inexistente) antes de implementar la clase.
- `./mvnw -q -o test -Dtest=AuthControllerTest,SecurityConfigTest,GetOwnProfileUseCaseTest,UserControllerTest` — 4 clases en verde (Task 3), confirma que `SecurityConfigTest.unauthenticatedRequestToNonPublicRouteReturns401ProblemJson` (que usa `/api/v1/users/me` como ejemplo desde la Story 1.1) sigue en verde tras añadir `UserController`.
- `./mvnw -q -o test -Dtest=ArchitectureRulesTest` — 3/3 en verde, sin violaciones de capas con las clases nuevas (`AccountNotFoundException` en `domain/exception` sigue sin dependencias de Spring).
- `./mvnw -o test -Dtest=UserProfileIntegrationTest` — 3/3 **skipped** (Docker no disponible en esta sesión — `@Testcontainers(disabledWithoutDocker = true)`), mismo comportamiento que el resto de la suite de integración (`AuthLoginIntegrationTest`, etc.) en este entorno; no es una regresión introducida por esta historia.
- `./mvnw -B -o verify` final (suite completa, sesión de desarrollo) — **155 tests, 0 failures, 0 errors, 33 skipped** (todos los `@Testcontainers`, Docker no disponible). JaCoCo `check` sobre `domain/`+`application/` en verde. `ArchitectureRulesTest` 3/3 en verde. `BUILD SUCCESS`.
- `./mvnw -B verify` con Docker disponible (code review, 2026-07-13, tras aplicar los patches) — **157 tests, 0 failures, 0 errors, 0 skipped**. `UserProfileIntegrationTest` 3/3 en verde contra PostgreSQL real (Testcontainers), incluida la cadena completa `JwtAuthenticationFilter → UserController → GetOwnProfileUseCase → AccountRepository`. `ArchitectureRulesTest` 3/3 en verde. JaCoCo `check` en verde. `BUILD SUCCESS`. Cierra la evidencia de integración pendiente señalada en el review.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Las 3 AC están implementadas: `GET /api/v1/users/me` con Access Token válido resuelve el `accountId` exclusivamente del claim `sub` autenticado y responde `200` con email/roles/estado/identidades federadas (vacías)/fecha de creación de la propia Cuenta (AC #1); sin token, con token inválido o expirado responde `401 application/problem+json` vía el deny-all ya existente, sin tocar `SecurityConfig` (AC #2, verificado con el test preexistente `SecurityConfigTest` y con el nuevo `UserProfileIntegrationTest`); un `sub` válido sin Cuenta correspondiente responde `401 application/problem+json` vía la nueva `AccountNotFoundException`, nunca `500` (AC #3).
- `GetOwnProfileUseCase` quedó con una sola dependencia (`AccountRepository`) y es de solo lectura (`@Transactional(readOnly = true)`) — primer caso de uso no mutante de este Epic.
- `ProfileResponse.federatedIdentities` es una lista vacía hardcodeada — comportamiento correcto mientras Epic 2 (Story 2.1, `backlog`) no exista; no se creó la tabla `federated_identities` ni ningún port/adapter para ella.
- No se tocó `SecurityConfig`, `JwtAuthenticationFilter` ni ninguna migración Flyway — `/api/v1/**` ya estaba protegido por el deny-all desde la Story 1.1, y `AccountRepository.findById` ya existía desde la Story 1.2.
- Docker no estaba disponible en esta sesión de desarrollo — los tests de integración (`@Testcontainers(disabledWithoutDocker = true)`) se compilaron y quedaron listos pero se ejecutaron en modo `skipped`, igual que el resto de la suite de integración existente en este mismo entorno. Recomendado re-ejecutar `./mvnw -B verify` con Docker Desktop disponible antes de dar la historia por verificada en CI.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/domain/exception/AccountNotFoundException.java`
- `src/main/java/com/auth_service/auth/application/usecase/GetOwnProfileCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/GetOwnProfileUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/ProfileResponse.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/UserController.java`
- `src/test/java/com/auth_service/auth/application/usecase/GetOwnProfileUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/UserControllerTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/UserProfileIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java` (nuevo `@ExceptionHandler(AccountNotFoundException.class)`)

## Change Log

| Fecha | Cambio |
|---|---|
| 2026-07-13 | Creación de la Story 1.7 a partir de epics.md, PRD FR-10, ARCHITECTURE-SPINE.md y continuidad con la Story 1.6. |
| 2026-07-13 | Implementación de la Story 1.7 (Tasks 1-4): `GET /api/v1/users/me` resuelve el perfil propio del titular a partir del claim `sub` del Access Token. |
