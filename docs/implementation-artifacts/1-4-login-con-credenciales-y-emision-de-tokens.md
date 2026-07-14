---
baseline_commit: 704433e6343a0b75344cee030bccb3988c781ff4
---

# Story 1.4: Login con credenciales y emisión de tokens

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a titular de una Cuenta activa,
I want iniciar sesión con mi email y contraseña,
so that reciba credenciales para usar las aplicaciones del ecosistema. (FR-3)

## Acceptance Criteria

1. Con credenciales válidas de una Cuenta `ACTIVE`, `POST /auth/login` invoca `LoginUseCase`, que a su vez invoca `TokenIssuer`, y la respuesta incluye un Access Token JWT HS256 (claims `sub`, `email`, `roles`, `iat`, `exp`, `iss`; TTL 15 min) y un Refresh Token opaco (AD-2, AD-3, AD-13). El Refresh Token se persiste solo como hash SHA-256, con `family_id` y `expires_at` a 7 días, en la tabla `refresh_tokens` creada por esta historia (AD-4). Todo token se emite exclusivamente vía `TokenIssuer` — ningún otro caso de uso construye JWTs (AD-2).
2. Con credenciales inválidas (email inexistente o contraseña errónea), `POST /auth/login` responde el mismo error genérico 401 en ambos casos (NFR-2).
3. Con una Cuenta `PENDING_VERIFICATION`, `LOCKED` o `DISABLED`, un intento de login con credenciales correctas es rechazado — con el mismo error genérico 401 que unas credenciales inválidas, sin distinguir el motivo (mismo patrón anti-enumeración de AC #2).
4. Con un Access Token válido, una petición a un endpoint protegido con `Authorization: Bearer <token>` es autenticada por el filtro JWT sin estado de sesión (NFR-5); sin token, con token inválido, mal firmado o expirado, la petición recibe 401 `application/problem+json` (comportamiento ya cubierto por el deny-all de `SecurityConfig`, Story 1.1 — esta historia añade el filtro que puede autenticar, no cambia el default).

## Tasks / Subtasks

- [x] Task 1: Config de TTLs y secretos JWT (AD-3, AD-10, AD-19) (AC: #1, #4)
  - [x] Nuevo `JwtProperties` (`config/`, `@ConfigurationProperties(prefix = "auth.jwt")`): `String secretCurrent`, `String secretPrevious`, `Duration accessTtl` (default 15 min si no viene configurado), `String issuer` (default `"auth-service"` si no viene configurado). El constructor compacto valida `secretCurrent` no nulo/blank — sin secreto de firma no hay forma segura de arrancar (falla rápido, no un `NullPointerException` tardío al primer login).
  - [x] Extender `AuthTokenProperties` (ya existe, prefix `auth.token`) con un nuevo campo `Duration refreshTtl`, default 7 días si no viene configurado — mismo patrón que `verificationTtl` (constructor compacto con default, sin anotación `@DurationUnit`, coherente con cómo Spring Boot ya parsea `24h` en esta clase).
  - [x] `application.properties`: añadir sección `auth.jwt.secret-current=${JWT_SECRET_CURRENT}`, `auth.jwt.secret-previous=${JWT_SECRET_PREVIOUS:}`, `auth.jwt.access-ttl=15m`, `auth.jwt.issuer=auth-service`, y `auth.token.refresh-ttl=7d` junto a la línea existente de `auth.token.verification-ttl`. `JWT_SECRET_CURRENT`/`JWT_SECRET_PREVIOUS` ya existen en `.env.example` desde la Story 1.1 (AD-19) — no hace falta tocar ese archivo.
  - [x] Tests unitarios de ambos records: defaults aplicados cuando el campo opcional viene nulo; `JwtProperties` lanza si `secretCurrent` es nulo o blank.
  - [x] **Bug real encontrado y corregido en el camino:** `@ConfigurationPropertiesScan` (Story 1.1) registra `JwtProperties` como bean para **todo** `@SpringBootTest` de contexto completo, no solo para producción — sin `auth.jwt.secret-current` resuelto, `AuthServiceApplicationTests`, `AuthControllerIntegrationTest` y `AuthVerificationIntegrationTest` (las tres ya existentes) rompían el arranque del contexto. Corregido añadiendo `@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")` a las tres — ninguna cambia de comportamiento, solo dejan de fallar por un secreto ausente que nada tenía que ver con lo que cada una prueba.

- [x] Task 2: Extender el port `PasswordHasher` (domain/port) con verificación (AC: #2)
  - [x] Nuevo método `boolean matches(String rawPassword, HashedPassword hashedPassword)` en la interfaz — hoy solo tiene `hash(RawPassword)`. Recibe `String`, **no** `RawPassword`, a propósito: `RawPassword` valida la política de contraseña (≥8, mayúscula, minúscula, dígito) en construcción, y ese chequeo pertenece al camino de *creación* (registro), no al de *verificación* (login) — ver razón completa en Task 8/Dev Notes.
  - [x] `BCryptPasswordHasher` (`infrastructure/adapters/security`, ya existe): implementa `matches` delegando en `passwordEncoder.matches(rawPassword, hashedPassword.value())` — `DelegatingPasswordEncoder` de Spring ya expone `matches`, no hay lógica nueva que escribir.
  - [x] Test unitario del adapter con un `PasswordEncoder` real (no mock): hash de una contraseña y `matches` contra la misma en verde, contra otra distinta en rojo, y contra un string que no cumple la política de `RawPassword` (p. ej. `"short"`) también en rojo sin lanzar excepción — confirma que `matches` no aplica ninguna validación de formato.

- [x] Task 3: Nuevo modelo de dominio `RefreshToken` (domain/model) (AD-4) (AC: #1)
  - [x] Clase inmutable, mismo estilo que `VerificationToken` (constructor privado, factories estáticas, Java puro — AD-1): campos `UUID id`, `AccountId accountId`, `String tokenHash`, `UUID familyId`, `Instant expiresAt`, `Instant usedAt`, `Instant revokedAt`.
  - [x] `static Issued issue(AccountId accountId, UUID familyId, Duration ttl, Clock clock)` — genera 32 bytes aleatorios con `SecureRandom` codificados en Base64 URL-safe sin padding (igual que `VerificationToken.generateRawToken`) y su hash SHA-256 hex (igual que `VerificationToken.sha256Hex`); devuelve `record Issued(String rawToken, RefreshToken token)`. El `familyId` se recibe como parámetro (no se genera dentro del método) porque un login crea una familia nueva (`UUID.randomUUID()`, decisión de `TokenIssuer`) pero la futura rotación de la Story 1.5 reutilizará este mismo método con la familia existente — evita tocar `RefreshToken` otra vez en esa historia.
  - [x] **No** añadir `reconstitute(...)` ni un método `consume`/`revoke` todavía — esta historia solo emite y guarda (`save`); los métodos de lectura/rotación llegan con la Story 1.5, siguiendo el mismo patrón incremental que `VerificationTokenRepository` tuvo entre la Story 1.2 (solo `save`) y la 1.3 (`findByTokenHash` + invalidación).
  - [x] Duplicar la lógica de generación/hash de token en vez de extraer un helper compartido con `VerificationToken` es una decisión deliberada: son ~15 líneas de JDK puro sin estado, y ambas clases documentan la única razón de negocio (AD-5) de forma independiente — no hay abstracción real que ganar todavía.
  - [x] Tests unitarios sin Spring: `issue(...)` con TTL positivo produce un token con `usedAt`/`revokedAt` nulos y `expiresAt` = ahora + ttl; `rawToken` nunca es igual a `tokenHash`; dos llamadas a `issue(...)` producen `rawToken`/`tokenHash` distintos; `issue(...)` con TTL nulo/cero/negativo lanza `IllegalArgumentException` (mismo contrato que `VerificationToken.issue`).

- [x] Task 4: Nuevo port `RefreshTokenRepository` (domain/port) (AD-4) (AC: #1)
  - [x] Interfaz con un único método por ahora: `RefreshToken save(RefreshToken refreshToken)`. Los métodos de búsqueda por hash y revocación de familia (necesarios para la Story 1.5) se añaden en esa historia, no aquí.

- [x] Task 5: Migración `V3__refresh_tokens.sql` (AD-4, AD-7) (AC: #1)
  - [x] Tabla `refresh_tokens`: `id uuid PRIMARY KEY`, `account_id uuid NOT NULL REFERENCES accounts (id)`, `token_hash text NOT NULL UNIQUE`, `family_id uuid NOT NULL`, `expires_at timestamptz NOT NULL`, `used_at timestamptz`, `revoked_at timestamptz`, `created_at timestamptz NOT NULL DEFAULT now()`.
  - [x] Índice `CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id)` — la Story 1.5 necesitará revocar toda una familia de una sola consulta (`UPDATE ... WHERE family_id = ?`), igual que `invalidateActiveTokens` de la Story 1.3 sobre `verification_tokens`.
  - [x] Sin índice adicional sobre `account_id`: no hay ningún AC en esta historia ni en la 1.5/1.6 que liste refresh tokens por Cuenta directamente (el logout y el reset de contraseña operan por `family_id`, ya indexado); no se añade especulativamente.

- [x] Task 6: Adapters de persistencia — `RefreshTokenEntity`, `RefreshTokenJpaRepository`, `RefreshTokenRepositoryAdapter` (`infrastructure/adapters/postgresql`) (AC: #1)
  - [x] `RefreshTokenEntity`: mismo estilo que `AccountEntity`/`VerificationTokenEntity` (constructor protegido sin argumentos para JPA, constructor público con todos los campos, getters, `@Table(name = "refresh_tokens")`).
  - [x] `RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID>` — sin métodos derivados adicionales todavía (solo `save` heredado, que es lo único que el port necesita).
  - [x] `RefreshTokenRepositoryAdapter implements RefreshTokenRepository`: `save(...)` mapea dominio→entidad y persiste. **No** implementar `toDomain(...)` (mapeo entidad→dominio) en esta historia — sin un método de lectura en el port, sería código muerto sin test posible; lo añade la Story 1.5 junto con `findByTokenHash`, análogo a como `VerificationTokenRepositoryAdapter` ganó su primer `toDomain` recién en la Story 1.3.

- [x] Task 7: `TokenIssuer` en `application/usecase` — punto único de emisión (AD-2, AD-3, AD-13) (AC: #1)
  - [x] Clase `@Service`, constructor-injected con `RefreshTokenRepository`, `JwtProperties`, `AuthTokenProperties`, `Clock`.
  - [x] `record IssuedTokens(String accessToken, String refreshToken)` — lo que `LoginUseCase` (y, más adelante, `FederatedLoginUseCase` en Epic 2 y `RefreshTokenUseCase` en la Story 1.5) reciben de vuelta.
  - [x] Método `IssuedTokens issue(Account account)` (login/registro de identidad nueva — crea familia nueva): construye el JWT con la API moderna de jjwt 0.13.0 (`Jwts.builder()`, no la API `set*` deprecada) — claims `subject(account.id().value().toString())`, `claim("email", account.email().value())`, `claim("roles", account.roles().stream().map(Enum::name).toList())`, `issuedAt(...)`, `expiration(...)`, `issuer(jwtProperties.issuer())`, firmado con `Jwts.SIG.HS256` y `Keys.hmacShaKeyFor(jwtProperties.secretCurrent().getBytes(StandardCharsets.UTF_8))` — la emisión firma **solo** con el secreto activo, nunca con el anterior (AD-19, el anterior solo sirve para validar durante la ventana de rotación, tarea del filtro de la Task 9).
  - [x] El mismo método genera el Refresh Token opaco vía `RefreshToken.issue(account.id(), UUID.randomUUID(), authTokenProperties.refreshTtl(), clock)` (familia nueva — este es el único punto del sistema hoy que crea familias; la Story 1.5 reutiliza `RefreshToken.issue` con una familia existente) y lo persiste con `refreshTokenRepository.save(issued.token())`.
  - [x] **No** implementar todavía un `issue(Account account, UUID existingFamilyId)` para renovar dentro de la misma familia — es exactamente el trabajo de `RefreshTokenUseCase` en la Story 1.5; añadir el overload ahora sin un llamador sería código sin test real.
  - [x] Tests unitarios sin mocks de JWT (parsear el token emitido con `Jwts.parser()` y el mismo secreto para verificar claims) — 32 bits mínimo de aleatoriedad ya cubiertos por los tests de `RefreshToken`: el JWT contiene `sub`/`email`/`roles`/`iss` correctos y `exp - iat` == `accessTtl` configurado; el Refresh Token devuelto es el `rawToken`, nunca el hash; `refreshTokenRepository.save` recibe un `RefreshToken` con `familyId` no nulo; dos llamadas a `issue(account)` para la misma Cuenta producen `familyId` distintos (dos logins = dos familias independientes, coherente con "cerrar sesión en un dispositivo no debe afectar a otro" — Non-Goal implícito hasta que 1.6 implemente logout).

- [x] Task 8: `LoginUseCase` en `application/usecase` (AD-6, AD-8, AD-13) (AC: #1, #2, #3)
  - [x] Nueva excepción `AuthenticationFailedException` (`domain/exception`, Java puro — mismo patrón que `DomainValidationException`) — necesita mapear a 401, no al 400 que ya usa `DomainValidationException`, así que no se reutiliza esa clase.
  - [x] `GlobalExceptionHandler`: nuevo `@ExceptionHandler(AuthenticationFailedException.class)` → `ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Email o contraseña incorrectos.")` — mensaje genérico fijo, igual en los tres casos de rechazo (AC #2, #3), sin `ex.getMessage()` variable que pudiera filtrar el motivo real por accidente.
  - [x] `record LoginCommand(String rawEmail, String rawPassword)`.
  - [x] Clase `@Service @Transactional`, constructor-injected con `AccountRepository`, `PasswordHasher`, `TokenIssuer`.
  - [x] Método `TokenIssuer.IssuedTokens login(LoginCommand command)` en este orden exacto: `new Email(command.rawEmail())` (normaliza mayúsculas, ya validado por el Value Object) → `accountRepository.findByEmail(email)`; si está vacío, lanza `AuthenticationFailedException` inmediatamente. Si existe, `passwordHasher.matches(new RawPassword(command.rawPassword())... )` — **cuidado**: `RawPassword` valida política de formato (≥8 chars, mayúscula, minúscula, dígito) en su constructor y lanzaría `DomainValidationException` (400) para una contraseña que simplemente no cumple el formato, lo cual filtraría información (un atacante distinguiría "formato inválido" de "no coincide"). Por eso el login **no** debe construir `RawPassword` para comparar — comparar el `String` crudo directamente contra el hash vía `passwordHasher.matches(String rawPassword, HashedPassword hashedPassword)` en vez del `RawPassword` tipado (ver ajuste de firma en Task 2: usar `String`, no `RawPassword`, específicamente para este método — a diferencia de `hash(RawPassword)` que sí exige la política porque es el camino de *creación* de contraseña, `matches` es el camino de *verificación* y debe aceptar cualquier string). Si no coincide, o si `account.status() != AccountStatus.ACTIVE`, lanza el mismo `AuthenticationFailedException` sin distinguir motivo (AC #3) — ambos chequeos comparten la misma rama de fallo por diseño, no por accidente. Si todo pasa, `tokenIssuer.issue(account)`.
  - [x] Tests unitarios con mocks — casos: credenciales válidas de Cuenta `ACTIVE` invoca `TokenIssuer.issue` y devuelve sus tokens; email inexistente lanza `AuthenticationFailedException` y `TokenIssuer` nunca se invoca; password incorrecta ídem; Cuenta `PENDING_VERIFICATION`/`LOCKED`/`DISABLED` con password correcta ídem (3 tests, uno por estado) — en los 5 casos de rechazo, `TokenIssuer.issue` nunca se llama.

- [x] Task 9: Filtro de autenticación JWT (`infrastructure/security`) (AD-3, AD-19, NFR-5) (AC: #4)
  - [x] Nuevo paquete `infrastructure/security` (el primero de esta capa — `BCryptPasswordHasher` vive en `infrastructure/adapters/security`, un paquete distinto; este filtro no es un adapter de un port de dominio, es infraestructura pura de Spring Security, por eso no comparte paquete).
  - [x] `JwtAuthenticationFilter extends OncePerRequestFilter`, constructor-injected con `JwtProperties`. En `doFilterInternal`: si no hay header `Authorization: Bearer <token>`, continúa la cadena sin tocar el `SecurityContext` (deja que el deny-all + `AuthenticationEntryPoint` de `SecurityConfig`, ya existente desde la Story 1.1, respondan 401 más adelante si la ruta lo requiere — este filtro nunca escribe una respuesta de error él mismo).
  - [x] Si hay token: intenta `Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secretCurrent bytes)).build().parseSignedClaims(token)`; si falla (firma inválida, expirado, malformado) **y** `jwtProperties.secretPrevious()` no es nulo/blank, reintenta con la clave anterior (AD-19, ventana de rotación); si ambos fallan (o no hay clave anterior configurada), continúa la cadena sin autenticar — igual que "sin token", nunca lanza ni escribe la respuesta directamente.
  - [x] Si algún parseo tiene éxito: extrae `sub` (UUID como string) y `roles` (lista de strings) de los claims, construye `List<GrantedAuthority>` con prefijo `ROLE_` (Consistency Conventions del spine) y setea `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(sub, null, authorities))` antes de continuar la cadena.
  - [x] `SecurityConfig` (UPDATE): inyecta `JwtAuthenticationFilter` y lo añade con `.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`. `PUBLIC_ENDPOINTS` no cambia — sigue sin haber ningún endpoint bajo `/api/v1/**` todavía (llega en la Story 1.7); esta historia solo deja el filtro listo para autenticar cuando exista uno.
  - [x] Tests unitarios del filtro (sin `@SpringBootTest`, construyendo `MockHttpServletRequest`/`MockHttpServletResponse`/`FilterChain` mock a mano): token válido con clave actual autentica; token válido firmado con la clave anterior autentica (rotación); token sin `Authorization` header no autentica y la cadena continúa igual; token expirado/mal firmado no autentica y la cadena continúa igual (nunca lanza).
  - [x] Test de integración a nivel de slice, mismo estilo que `SecurityConfigTest` (`@WebMvcTest` con un `NoOpController` protegido, `@Import({SecurityConfig.class, JwtAuthenticationFilter.class})` o el bean equivalente): un JWT válido emitido con el mismo `JwtProperties` de test autentica la petición (200, no 401); sin token, 401 problem+json (regresión del comportamiento ya cubierto por la Story 1.1).
  - [x] **Bug real encontrado y corregido en el camino:** un `@RestController` anidado estático dentro de la propia clase de test (`SecurityConfigTest.NoOpController`, tal como lo dejó la Story 1.1) **nunca se registra** como bean bajo `@WebMvcTest(controllers = ...)` en esta versión de Spring Boot — el primer test que de verdad ejercitó una ruta mapeada en él (`validAccessTokenAuthenticatesAProtectedRoute`) devolvía 404 en vez de 200 pese a que el filtro autenticaba correctamente. Los dos tests preexistentes de esa clase nunca lo notaron porque ninguno dependía de que el controller existiera (ambos solo verifican el deny-all). Corregido extrayendo el controller a su propio archivo top-level (`NoOpTestController.java`, `src/test/java`) — con eso `RequestMappingHandlerMapping` sí lo registra. `SecurityConfigTest` (UPDATE) apunta ahora a esa clase.

- [x] Task 10: Controller REST — `POST /auth/login` (AC: #1, #2, #3)
  - [x] `infrastructure/controller/dto/LoginRequest(String email, String password)` con `@NotBlank` en ambos — sin validación de formato de password aquí (a diferencia de `RegisterRequest`): un login con una contraseña que no cumple la política igual debe intentar compararse contra el hash y fallar como credencial inválida genérica, no como 400 de formato (ver razón en Task 8).
  - [x] `infrastructure/controller/dto/LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds)` — `tokenType` fijo `"Bearer"`; `expiresInSeconds` calculado desde `jwtProperties.accessTtl()` (el controller no lo inventa, lo recibe o lo deriva de la misma config inyectada).
  - [x] `AuthController` (UPDATE): nuevo endpoint `POST /auth/login` invoca `loginUseCase.login(new LoginCommand(request.email(), request.password()))`, devuelve `200 OK` con `LoginResponse`. `AuthenticationFailedException` se deja propagar hacia `GlobalExceptionHandler` (Task 8) — el controller no captura nada aquí, a diferencia del `DataIntegrityViolationException` que sí captura en `/register` (ese es un caso de carrera real, esto es simplemente dejar que la excepción tipada haga su trabajo).
  - [x] `AuthControllerTest` (UPDATE): añadir `@MockitoBean LoginUseCase` — sin esto, Spring no puede construir `AuthController` en el slice test acotado (mismo ajuste que tuvo que hacer la Story 1.3 con sus dos casos de uso nuevos).

- [x] Task 11: Tests de integración (AC: #1, #2, #3, #4)
  - [x] `AuthLoginIntegrationTest` — `@SpringBootTest` + Testcontainers + `@Transactional`, mismo patrón que `AuthVerificationIntegrationTest`. Precondición de cada test: crear una Cuenta directamente vía `AccountRepository`/`PasswordHasher` reales (no vía `POST /auth/register`, por el mismo límite de `@TransactionalEventListener(AFTER_COMMIT)` bajo `@Transactional` de test ya documentado en la Story 1.2/1.3) con el status que cada caso necesite.
  - [x] Casos: login con Cuenta `ACTIVE` y credenciales correctas → 200, `accessToken` parseable con el `JwtProperties` de test y claims correctos, `refreshToken` no vacío, y existe una fila en `refresh_tokens` cuyo hash coincide con `RefreshToken.hashRawToken`-equivalente del `refreshToken` devuelto (mismo patrón de verificación indirecta que uso `AuthVerificationIntegrationTest` para no depender del listener post-commit).
  - [x] Email inexistente → 401 problem+json con el mensaje genérico.
  - [x] Password incorrecta sobre Cuenta `ACTIVE` existente → 401 mismo mensaje.
  - [x] Cuenta `PENDING_VERIFICATION` con password correcta → 401 mismo mensaje (no `ACCEPTED`, no distinto de los anteriores).
  - [x] Cuenta `LOCKED` (con `lockedUntil` en el futuro) con password correcta → 401 mismo mensaje.
  - [x] Cuenta `DISABLED` con password correcta → 401 mismo mensaje.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Stories 1.1/1.2/1.3 y cobertura JaCoCo de `domain/`+`application/` ≥80% (NFR-4, AD-21).

### Review Findings

- [x] [Review][Resuelto] Los 4 `@SpringBootTest` con Testcontainers no se ejecutaron durante el desarrollo por falta de Docker — **sí se ejecutaron durante esta code review** (Docker disponible en esta sesión): `./mvnw -B verify` completo en verde, incluyendo los 6 casos de `AuthLoginIntegrationTest` contra Postgres real (refresh token persistido con el hash correcto verificado end-to-end), JaCoCo y ArchUnit sin violaciones [src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginIntegrationTest.java]
- [x] [Review][Defer] Canal lateral de timing en `/auth/login`: `LoginUseCase` solo ejecuta la comparación BCrypt (costosa) cuando la Cuenta existe; email inexistente responde casi de inmediato. Mismo cuerpo 401 en ambos casos, pero latencia distinguible [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:37-44] — deferred, consistente con Story 1.2/1.3; mitigación de costo equivalente requiere diseño dedicado, no trivial en este alcance
- [x] [Review][Patch] Email con formato inválido responde 400 con mensaje revelador en vez del 401 genérico mandado por AC #2/#3 — `new Email(command.rawEmail())` sin capturar `DomainValidationException` [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:35]
- [x] [Review][Patch] `PasswordHasher.matches()` puede lanzar `IllegalArgumentException` para un hash con formato no reconocido — sin capturar, se convierte en 500 en vez del 401 genérico [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:43]
- [x] [Review][Patch] El mensaje de `AuthenticationFailedException` en la rama de status no-ACTIVE incluye `account.status()` — el cuerpo de la respuesta hoy es seguro (`GlobalExceptionHandler` lo ignora), pero la excepción en sí lleva la señal de enumeración lista para filtrarse en el primer `log.warn(ex.getMessage())` que alguien añada [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:47]
- [x] [Review][Patch] `accessTtl`/`refreshTtl` están protegidos contra `null` pero no contra valores configurados en cero o negativos — un `auth.jwt.access-ttl=0s` o `auth.token.refresh-ttl=0s` mal configurado pasa el binding y falla más tarde (token con `exp <= iat` silenciosamente, o `IllegalArgumentException` sin capturar en el primer login) [src/main/java/com/auth_service/auth/config/JwtProperties.java:20-22, src/main/java/com/auth_service/auth/config/AuthTokenProperties.java:16-21]
- [x] [Review][Patch] `JwtAuthenticationFilter` nunca valida el claim `iss` que `TokenIssuer` sí se toma la molestia de fijar — cualquier JWT HS256 firmado con el mismo secreto desde cualquier origen valida aquí sin importar quién dice haberlo emitido [src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java:74-78]
- [x] [Review][Patch] Los fallos de validación JWT (expirado, mal formado, firma incorrecta, fuera de ventana de rotación) se descartan en silencio sin ningún log — imposible distinguir en producción "rotaron el secreto" de "alguien está probando tokens basura" [src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java:58-72]
- [x] [Review][Patch] Un claim `roles` de tipo inesperado lanza `RequiredTypeException` sin capturar en `authenticate()`, rompiendo el contrato documentado de "nunca lanza" del filtro y terminando en 500 [src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java:83]
- [x] [Review][Patch] Un token válidamente firmado sin claim `sub` autentica la petición con un principal nulo en vez de rechazarla [src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java:82,88-89]
- [x] [Review][Patch] `refresh_tokens` no tiene índice sobre `account_id` — cualquier futura consulta de "listar sesiones de esta cuenta" hace full scan [src/main/resources/db/migration/V3__refresh_tokens.sql:1-12]
- [x] [Review][Patch] `LoginRequest` no tiene cota superior de longitud en el password antes de reenviarlo a la comparación BCrypt [src/main/java/com/auth_service/auth/infrastructure/controller/dto/LoginRequest.java:11-14]
- [x] [Review][Patch] `expiresInSeconds` en la respuesta se calcula de forma independiente del token realmente emitido (`AuthController` lee la config directo en vez de derivarlo del `exp` real de `TokenIssuer`) — hoy coinciden solo por coincidencia [src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java:95, src/main/java/com/auth_service/auth/application/usecase/TokenIssuer.java:60]
- [x] [Review][Patch] `RefreshToken.issue()` construye un `new SecureRandom()` en cada llamada en vez de reutilizar una instancia compartida [src/main/java/com/auth_service/auth/domain/model/RefreshToken.java:65]
- [x] [Review][Defer] Secreto JWT débil/corto no se rechaza al arranque — falla en runtime con `WeakKeyException` sin capturar, no solo en `TokenIssuer` (login) sino también en `JwtAuthenticationFilter.parseWithKey` (toda petición autenticada), ya que `WeakKeyException` no es `JwtException`/`IllegalArgumentException`. Ya trackeado en `deferred-work.md` desde Story 1.1; los Dev Notes de esta historia ya reconocen que la cobertura es parcial y difieren la validación fail-fast completa a un ticket aparte [src/main/java/com/auth_service/auth/config/JwtProperties.java:16-26, src/main/java/com/auth_service/auth/application/usecase/TokenIssuer.java:58, src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java:61,74-75] — deferred, pre-existing
- [x] [Review][Defer] Sin rate limiting ni bloqueo por fuerza bruta en `/auth/login` — explícitamente alcance de Story 3.2 [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — deferred, pre-existing
- [x] [Review][Defer] `lockedUntil` nunca se revisa por expiración durante el login — una Cuenta `LOCKED` queda rechazada para siempre aunque su ventana de bloqueo ya haya pasado. Explícitamente fuera de alcance según los propios Dev Notes de Story 1.3 ("No se tocan failedAttempts/lockedUntil — fuera de alcance (Story 3.2)") [src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java:46, src/main/java/com/auth_service/auth/domain/model/Account.java] — deferred, pre-existing
- [x] [Review][Defer] El FK `refresh_tokens.account_id` no tiene política `ON DELETE` explícita (default `RESTRICT`) — irrelevante hoy porque no existe ningún flujo de borrado de cuenta, pero requerirá una decisión (`CASCADE` vs `SET NULL` vs mantener `RESTRICT`) cuando exista [src/main/resources/db/migration/V3__refresh_tokens.sql:3] — deferred, pre-existing
- [x] [Review][Defer] La lógica de generación de token (SecureRandom + Base64 + SHA-256) está duplicada entre `RefreshToken` y `VerificationToken` en vez de compartida — tradeoff deliberado y documentado en los Dev Notes de esta misma historia ("no hay abstracción real que ganar todavía"), pero no hay ningún test que garantice que ambas se mantengan equivalentes [src/main/java/com/auth_service/auth/domain/model/RefreshToken.java:64-84] — deferred, pre-existing

## Dev Notes

- **Continuidad con las Stories 1.1-1.3 (done):** `Account`, `AccountRepository` (`findByEmail`, `findById`, `save` ya existen), `Email`, `HashedPassword`, `RawPassword`, `PasswordHasher.hash`, `AuthTokenProperties`, el bean `Clock`, `GlobalExceptionHandler`, `SecurityConfig` con `PUBLIC_ENDPOINTS` — reutilízalos, no los reimplementes. `TokenIssuer` y `LoginUseCase` ya estaban previstos con esos nombres exactos en `ARCHITECTURE-SPINE.md#Capability-→-Architecture-Map`.
- **Esta es la primera historia que introduce JWT real.** No existe hoy ningún código de firma/validación de tokens en el repo — `TokenIssuer` y `JwtAuthenticationFilter` son ambos completamente nuevos, a diferencia de las Stories 1.2→1.3 que extendían clases ya existentes.
- **`PasswordHasher.matches` recibe `String`, no `RawPassword` — decisión deliberada, no descuido.** `RawPassword` valida la política de contraseña (≥8, mayúscula, minúscula, dígito) en su constructor porque existe para el camino de *creación* (registro, futura Story 3.1 de reset). El login es el camino de *verificación*: una contraseña que no cumple la política hoy pudo haber sido válida cuando la Cuenta se creó bajo una política anterior, y en cualquier caso construir un `RawPassword` solo para comparar forzaría a `LoginUseCase` a manejar `DomainValidationException` como un tercer tipo de error 400 que rompería el anti-enumeración de AC #2/#3 (revelaría "tu password no tiene mayúscula" en vez de "credenciales inválidas"). Por eso `matches(String, HashedPassword)` acepta cualquier string tal cual llega del DTO.
- **Orden de chequeos en `LoginUseCase` importa, igual que en `VerifyAccountUseCase` (Story 1.3):** cuenta inexistente → password no coincide → status inválido, todos terminan en la misma excepción `AuthenticationFailedException` sin bifurcación de mensaje. Esto es lo que garantiza AC #2 y #3 por construcción — no hay un `if` que decida "revelar más" en ningún caso.
- **`TokenIssuer.issue(Account)` genera una familia de Refresh Token nueva en cada llamada.** Eso es correcto para login (cada sesión nueva es una familia nueva) y para el futuro login federado (Epic 2). La renovación dentro de la misma familia (Story 1.5) es una responsabilidad distinta (`RefreshTokenUseCase`) que reutilizará `RefreshToken.issue(accountId, familyId, ttl, clock)` con el `familyId` existente en vez de uno nuevo — por eso ese parámetro ya es explícito desde esta historia.
- **AD-19 (rotación de `JWT_SECRET`) se cumple parcialmente en esta historia:** la *validación* (filtro) ya acepta clave actual + anterior; la *emisión* (`TokenIssuer`) firma solo con la actual, tal como exige el spine. Lo que sigue fuera de alcance (y no lo pide ningún AC de esta historia) es un mecanismo de *rotación operativa* en sí (rotar la env var y redeploy) — eso es un procedimiento operacional, no código.
- **Riesgo heredado, no de esta historia:** el placeholder `JWT_SECRET_CURRENT=change-me-to-a-256-bit-random-secret` de `.env.example` sigue sin una validación fail-fast explícita contra su propio valor por defecto — ya estaba anotado en `deferred-work.md` desde la Story 1.1 como "pertenece a la historia que conecte la firma/validación JWT (Epic 1, Story 1.4+)". Esta historia conecta la firma/validación pero **no** añade esa validación adicional: `Keys.hmacShaKeyFor` de jjwt ya falla rápido (`WeakKeyException`) si el secreto tiene menos de 256 bits, que es una redundancia parcial pero no cubre "es literalmente el placeholder documentado". Si se decide cerrar ese ítem, es una historia/task aparte, no bloqueante aquí — mantenlo en `deferred-work.md` sin marcarlo resuelto.
- **No implementar todavía:** revocación de familia, detección de reuso de refresh token (`used_at`, `revoked_at` se persisten pero nada los lee ni los muta en esta historia — eso es literalmente el contenido de la Story 1.5), logout, ni el endpoint `/api/v1/users/me` (Story 1.7, es lo que finalmente ejercitará el filtro JWT con un recurso real protegido — hasta entonces el filtro se prueba con un `NoOpController` de test, igual que `SecurityConfigTest` de la Story 1.1).

### Project Structure Notes

- Paquete nuevo: `infrastructure/security` (solo `JwtAuthenticationFilter`) — distinto de `infrastructure/adapters/security` (donde vive `BCryptPasswordHasher`, un adapter de un port de dominio). El filtro no implementa ningún port de `domain/`, es infraestructura de Spring Security pura, por eso no encaja en `adapters/`.
- Todos los demás archivos nuevos caen en paquetes ya existentes desde la Story 1.1 (`domain/model`, `domain/port`, `domain/exception`, `application/usecase`, `infrastructure/adapters/postgresql`, `infrastructure/controller/dto`, `config`).
- `AuthController`, `SecurityConfig`, `AuthTokenProperties`, `GlobalExceptionHandler`, `AuthControllerTest` son UPDATE (no NEW) — todos ya existen y esta historia los extiende sin reescribirlos.
- Nueva migración `V3__refresh_tokens.sql` sigue la numeración secuencial de Flyway (`V1__init.sql`, `V2__accounts.sql` ya existen).

### References

- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-2] — punto único de emisión de tokens, `TokenIssuer`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-3] — Access Token HS256, claims fijas, TTL 15 min
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-4] — Refresh Token opaco, hasheado, family_id, TTL 7 días
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-8] — Problem Details y anti-enumeración
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso como frontera transaccional
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-19] — rotación de `JWT_SECRET`, par activo+anterior
- [Source: docs/planning-artifacts/epics.md#Story-1.4] — criterios de aceptación originales (Given/When/Then)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md] — FR-3, NFR-1, NFR-2, NFR-5
- [Source: docs/implementation-artifacts/1-3-verificacion-de-email-y-reenvio.md] — patrón de token opaco hasheado (`VerificationToken`), anti-enumeración, orden de chequeos antes de mutar estado
- [Source: docs/implementation-artifacts/deferred-work.md] — ítem de validación fail-fast del placeholder de `JWT_SECRET_CURRENT`, anotado desde la Story 1.1 como propio de esta historia (parcialmente cubierto, ver Dev Notes)
- [Source: .env.example] — `JWT_SECRET_CURRENT`, `JWT_SECRET_PREVIOUS` ya definidos desde la Story 1.1

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -q -B compile` / `test-compile` iterativos mientras se implementaba cada task — 2 errores de compilación menores (import roto por un `replace_all` descuidado en `JwtAuthenticationFilter`, import de `UsernamePasswordAuthenticationFilter` en el paquete equivocado en `SecurityConfig`), corregidos antes de continuar.
- `./mvnw -B test` (suite completa) — dos fallos reales encontrados y corregidos, ambos documentados también en las Tasks correspondientes:
  1. `TokenIssuerTest.issueBuildsAJwtWithTheExpectedClaims` fallaba con `ExpiredJwtException`: el test firmaba el JWT con un `Clock` fijo (2026-07-10) pero `Jwts.parser()` valida `exp` contra el reloj real del sistema por defecto. Corregido con `.clock(() -> Date.from(Instant.now(clock)))` en el parser de test.
  2. `SecurityConfigTest.validAccessTokenAuthenticatesAProtectedRoute` devolvía 404 — diagnosticado con `RequestMappingHandlerMapping.getHandlerMethods()` inyectado temporalmente en el test: el `@RestController` anidado estático nunca se registraba como bean bajo `@WebMvcTest(controllers=...)`. Confirmado extrayéndolo a una clase top-level (`NoOpTestController`), que sí se registra correctamente.
- `./mvnw -B verify` final — 96/96 tests en verde (17 skipped: Testcontainers sin Docker disponible en este entorno de ejecución — se skippean automáticamente vía `@Testcontainers(disabledWithoutDocker = true)`, no fallan). JaCoCo `check` sobre `domain/`+`application/` — **"All coverage checks have been met"**. ArchUnit (`ArchitectureRulesTest`) 3/3 en verde, sin violaciones de capas. `BUILD SUCCESS`.
- **Limitación de este entorno:** Docker Desktop no estaba accesible desde la sesión de ejecución (`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` no respondió), así que los 4 tests `@SpringBootTest` con Testcontainers (`AuthServiceApplicationTests`, `AuthControllerIntegrationTest`, `AuthVerificationIntegrationTest`, `AuthLoginIntegrationTest`) no se ejecutaron localmente — se skippean limpiamente, no fallan. Correrán en CI (`.github/workflows/ci.yml`, que sí tiene Docker). Recomendado: correrlos manualmente contra Docker real antes de dar la historia por completamente verificada end-to-end.
- **Actualización durante code review (2026-07-11):** Docker sí estaba disponible en la sesión de revisión — `./mvnw -B verify` se corrió completo con los 4 `@SpringBootTest` de Testcontainers en verde (0 skipped), incluidos los 6 casos de `AuthLoginIntegrationTest` contra Postgres real. JaCoCo y ArchUnit también en verde. La historia queda verificada end-to-end, no solo a nivel unitario.

### Completion Notes List

- Las 4 AC están implementadas: login emite Access Token JWT HS256 + Refresh Token opaco vía `TokenIssuer` (AC #1); credenciales inválidas y Cuentas no-`ACTIVE` responden el mismo 401 genérico sin distinguir motivo (AC #2, #3); el filtro JWT autentica peticiones con Bearer token válido, incluido durante la ventana de rotación con la clave anterior (AC #4).
- `PasswordHasher.matches` recibe `String`, no `RawPassword` — decisión deliberada documentada en la propia historia (Task 2/8) para no filtrar errores de formato de contraseña como una tercera clase de error en el login.
- `TokenIssuer` es el único punto de emisión de JWTs (AD-2): `LoginUseCase` no construye tokens directamente, solo orquesta y delega.
- Dos bugs reales encontrados y corregidos durante la implementación (no en un code review posterior): el arranque de `JwtProperties` rompía los 3 `@SpringBootTest` de contexto completo ya existentes (corregido con `@TestPropertySource` en los tres), y el `NoOpController` anidado de `SecurityConfigTest` nunca se registraba como bean bajo `@WebMvcTest` (corregido extrayéndolo a una clase top-level `NoOpTestController`) — ambos detallados en Debug Log References y en las Tasks correspondientes.
- No se implementó (deliberadamente fuera de alcance, documentado en Dev Notes): revocación de familia, detección de reuso de refresh token, logout, ni el endpoint `/api/v1/users/me` — llegan en Stories 1.5, 1.6 y 1.7 respectivamente.
- Tests de integración con Testcontainers (`AuthLoginIntegrationTest`, y los 3 preexistentes ahora también con `@TestPropertySource`) no se ejecutaron localmente por falta de Docker en este entorno — quedan pendientes de verificación real en CI o en una máquina con Docker Desktop activo.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/config/JwtProperties.java`
- `src/main/java/com/auth_service/auth/domain/model/RefreshToken.java`
- `src/main/java/com/auth_service/auth/domain/port/RefreshTokenRepository.java`
- `src/main/java/com/auth_service/auth/domain/exception/AuthenticationFailedException.java`
- `src/main/java/com/auth_service/auth/application/usecase/TokenIssuer.java`
- `src/main/java/com/auth_service/auth/application/usecase/LoginCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilter.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenEntity.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenJpaRepository.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenRepositoryAdapter.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/LoginRequest.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/LoginResponse.java`
- `src/main/resources/db/migration/V3__refresh_tokens.sql`
- `src/test/java/com/auth_service/auth/config/JwtPropertiesTest.java`
- `src/test/java/com/auth_service/auth/config/AuthTokenPropertiesTest.java`
- `src/test/java/com/auth_service/auth/config/NoOpTestController.java`
- `src/test/java/com/auth_service/auth/domain/model/RefreshTokenTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/TokenIssuerTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/LoginUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/adapters/security/BCryptPasswordHasherTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/security/JwtAuthenticationFilterTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthLoginIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/config/AuthTokenProperties.java` (`refreshTtl`)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (wiring de `JwtAuthenticationFilter`)
- `src/main/java/com/auth_service/auth/domain/port/PasswordHasher.java` (`matches`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/security/BCryptPasswordHasher.java` (`matches`)
- `src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java` (`AuthenticationFailedException` → 401)
- `src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java` (`POST /auth/login`)
- `src/main/resources/application.properties` (`auth.jwt.*`, `auth.token.refresh-ttl`)
- `src/test/java/com/auth_service/auth/application/usecase/RegisterAccountUseCaseTest.java` (constructor de `AuthTokenProperties` con 2 args)
- `src/test/java/com/auth_service/auth/application/usecase/ResendVerificationUseCaseTest.java` (constructor de `AuthTokenProperties` con 2 args)
- `src/test/java/com/auth_service/auth/config/SecurityConfigTest.java` (JWT filter wiring, tests AC #4, fix del `NoOpController`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (`LoginUseCase` mockeado, tests de `/auth/login`)
- `src/test/java/com/auth_service/auth/AuthServiceApplicationTests.java` (`@TestPropertySource` para `JwtProperties`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerIntegrationTest.java` (`@TestPropertySource` para `JwtProperties`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthVerificationIntegrationTest.java` (`@TestPropertySource` para `JwtProperties`)
