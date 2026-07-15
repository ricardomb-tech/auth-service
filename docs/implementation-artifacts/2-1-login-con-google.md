---
baseline_commit: 704433e6343a0b75344cee030bccb3988c781ff4
---

# Story 2.1: Login con Google

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a visitante,
I want continuar con mi cuenta de Google,
so that acceda sin crear ni recordar otra contraseña. (FR-6)

## Acceptance Criteria

1. Dado un visitante sin Cuenta previa, al completar con éxito el flujo `/oauth2/authorization/google`, `FederatedLoginUseCase` crea una Cuenta `ACTIVE` con Rol `USER`, **sin contraseña local** (`password_hash NULL`), con una Identidad Federada `google` persistida en la nueva tabla `federated_identities` (AD-13), y el visitante recibe el mismo par Access+Refresh que emite `TokenIssuer` para el login con credenciales (AD-2) — mismo shape de claims (`sub`, `email`, `roles`, AD-3); el token no distingue el método de autenticación.
2. Dado una Cuenta local existente cuyo email coincide con el email verificado (`email_verified = true`) devuelto por Google, al completar el login con Google la Identidad Federada se vincula a esa Cuenta existente sin duplicarla — logins posteriores por credenciales o por Google acceden a la misma Cuenta y a la misma Identidad Federada (no se crea una segunda fila en `federated_identities` para el mismo `(provider, provider_user_id)`).
3. Dado un fallo o cancelación del flujo en Google (denegación de consentimiento, error en el intercambio del código, email no verificado por el proveedor), al retornar el flujo con error no se crea ningún estado parcial (ninguna fila huérfana en `accounts` ni en `federated_identities`) y el visitante recibe un error controlado (AD-8) — nunca un stack trace ni un `500`.

## Tasks / Subtasks

- [x] Task 1: Migración Flyway `V4__federated_identities.sql` (AC: #1, #2)
  - [x] Tabla `federated_identities`: `id uuid PRIMARY KEY`, `account_id uuid NOT NULL REFERENCES accounts (id)`, `provider text NOT NULL`, `provider_user_id text NOT NULL`, `created_at timestamptz NOT NULL DEFAULT now()`.
  - [x] `UNIQUE (provider, provider_user_id)` — el ER del Architecture Spine marca `provider_user_id` como `UK` suelto, pero un mismo `provider_user_id` numérico puede repetirse entre proveedores distintos; la unicidad funcionalmente correcta es la combinación, no la columna aislada.
  - [x] `CREATE INDEX idx_federated_identities_account_id ON federated_identities (account_id)` — mismo patrón de índice que `V3__refresh_tokens.sql` sobre su FK.
  - [x] **No** tocar `accounts`/`account_roles` (V2) — `accounts.password_hash` ya es `text` sin `NOT NULL` desde la Story 1.2, ya preparado para Cuentas sin contraseña local.

- [x] Task 2: Dominio — `FederatedProvider`, `FederatedIdentity`, `FederatedIdentityRepository` (AC: #1, #2)
  - [x] `domain/model/FederatedProvider.java` — `public enum FederatedProvider { GOOGLE, GITHUB }` (GitHub ya se declara aunque esta historia solo implementa Google — Story 2.2 reutiliza el mismo enum, no lo crea).
  - [x] `domain/model/FederatedIdentity.java` — Java puro (AD-1), mismo patrón exacto que `RefreshToken.java`: campos `id (UUID)`, `accountId (AccountId)`, `provider (FederatedProvider)`, `providerUserId (String)`, `createdAt (Instant)`; constructor privado; factory `public static FederatedIdentity link(AccountId accountId, FederatedProvider provider, String providerUserId, Clock clock)` (genera `UUID.randomUUID()`, `Instant.now(clock)`); `public static FederatedIdentity reconstitute(...)` para el adapter de persistencia (mismo rol que `RefreshToken.reconstitute`).
  - [x] `domain/port/FederatedIdentityRepository.java`:
    ```java
    public interface FederatedIdentityRepository {
        FederatedIdentity save(FederatedIdentity federatedIdentity);
        Optional<FederatedIdentity> findByProviderAndProviderUserId(FederatedProvider provider, String providerUserId);
        List<FederatedIdentity> findByAccountId(AccountId accountId);
    }
    ```
    `findByAccountId` no lo usa esta historia para el login en sí, pero lo necesita la Task 8 (exponer identidades reales en `/api/v1/users/me`) — añadirlo ahora evita una segunda migración de puerto.

- [x] Task 3: Dominio — `Account.registerFederated(Email)` + **corrección de regresión en `LoginUseCase`** (AC: #1, #3)
  - [x] Nuevo factory en `Account.java`: `public static Account registerFederated(Email email)` — nace **`ACTIVE`** directamente (no `PENDING_VERIFICATION`: el proveedor ya verificó el email, no hay nada que confirmar por correo), Rol `USER`, `passwordHash = null`. El constructor privado ya acepta `HashedPassword passwordHash` sin validar no-nulidad (`HashedPassword` solo valida en su propio constructor compacto, que no se invoca al pasar una referencia `null`) — confirmado leyendo `Account.java` y `HashedPassword.java` actuales; no requiere cambiar la clase `HashedPassword` ni el constructor privado de `Account`.
  - [x] **Regresión a corregir, no opcional:** `LoginUseCase.login()` línea `passwordHasher.matches(command.rawPassword(), account.passwordHash())` asume hoy que `passwordHash()` nunca es `null`. En cuanto exista una Cuenta federada-only (esta historia), un intento de `/auth/login` con ese email lanza `NullPointerException` dentro de `BCryptPasswordHasher.matches` (`hashedPassword.value()` sobre `null`) — no cae en el `catch (IllegalArgumentException)` existente, escapa como `500` al `GlobalExceptionHandler.handleUnexpectedException`. Peor: un `500` en vez del `401` genérico **es** una fuga de enumeración (revela "este email no tiene contraseña local", violando NFR-2/AD-8 que esta misma clase ya protege para los demás casos). Añadir un chequeo `if (account.passwordHash() == null) throw new AuthenticationFailedException(INVALID_CREDENTIALS_MESSAGE);` **antes** de invocar `passwordHasher.matches(...)`, mismo mensaje genérico que los demás caminos de rechazo. Cubrir con un test nuevo en `LoginUseCaseTest`: Cuenta `ACTIVE` con `passwordHash == null` + intento de login con cualquier password → `AuthenticationFailedException`, nunca `NullPointerException`.
  - [x] Tests unitarios nuevos en `AccountTest` (o crear el archivo si no existe): `registerFederated` produce Cuenta `ACTIVE` (no `PENDING_VERIFICATION`), Rol `{USER}`, `passwordHash() == null`.

- [x] Task 4: Infraestructura de persistencia — `FederatedIdentityEntity` + `FederatedIdentityJpaRepository` + `FederatedIdentityRepositoryAdapter` (AC: #1, #2)
  - [x] Mismo patrón exacto que `RefreshTokenEntity`/`RefreshTokenRepositoryAdapter` (entidad JPA en `infrastructure/adapters/postgresql/`, nunca cruza a `domain/` — AD-1).
  - [x] `FederatedIdentityJpaRepository extends JpaRepository<FederatedIdentityEntity, UUID>` con `Optional<FederatedIdentityEntity> findByProviderAndProviderUserId(String provider, String providerUserId)` y `List<FederatedIdentityEntity> findByAccountId(UUID accountId)` (Spring Data derivado, sin `@Query` necesaria).
  - [x] `AccountRepositoryAdapter.toEntity`/`toDomain` **no cambian** — ya manejan `passwordHash == null` correctamente en ambas direcciones (verificado en el código actual: `account.passwordHash() != null ? account.passwordHash().value() : null` y su inverso simétrico); esta historia no toca ese archivo.

- [x] Task 5: `SecurityConfig` — rutas públicas OAuth2 + `AuthorizationRequestRepository` sin sesión (AC: #1, #2, #3)
  - [x] Añadir a `PUBLIC_ENDPOINTS`: `/oauth2/**` (inicia el flujo — `/oauth2/authorization/google`) y `/login/oauth2/**` (callback por defecto de Spring Security — `/login/oauth2/code/google`). El propio Javadoc de la clase ya anticipa esto: *"Las historias que introducen /auth/\*\* y /oauth2/\*\* amplían esta lista"*.
  - [x] **Decisión de diseño obligatoria — mantener `SessionCreationPolicy.STATELESS`, no cambiarla:** `oauth2Login()` de Spring Security por defecto persiste el `OAuth2AuthorizationRequest` (state + PKCE) en la `HttpSession` entre la redirección a Google y el callback — incompatible con `STATELESS` (AD-3, ya establecido para todo el servicio). La solución estándar (confirmada por investigación externa, no inventada) es implementar un `AuthorizationRequestRepository<OAuth2AuthorizationRequest>` propio que serialice la request en una **cookie** de corta vida en vez de la sesión — patrón conocido como "Cookie-based Authorization Request Repository". Crear `infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java` implementando `saveAuthorizationRequest`/`loadAuthorizationRequest`/`removeAuthorizationRequest` sobre una cookie firmada/serializada (Base64 de la request), TTL corto (p. ej. 3 minutos), `httpOnly`, `secure` condicionado a HTTPS. Registrarlo vía `.oauth2Login(oauth2 -> oauth2.authorizationEndpoint(a -> a.authorizationRequestRepository(cookieRequestRepository)))`.
  - [x] Configurar `.oauth2Login(oauth2 -> oauth2.authorizationEndpoint(...).successHandler(oAuth2AuthenticationSuccessHandler).failureHandler(oAuth2AuthenticationFailureHandler))` en la misma `securityFilterChain(HttpSecurity http)` — no crear una segunda `SecurityFilterChain`, el deny-all (AD-11) debe seguir siendo uno solo.
  - [x] **Contrato a mantener sincronizado** (mismo aviso que ya trae el Javadoc de la clase para el `authenticationEntryPoint`/`accessDeniedHandler`): un fallo dentro del success handler (p. ej. `FederatedLoginUseCase` lanza `FederatedLoginFailedException`) **no** pasa por `GlobalExceptionHandler` — el success handler corre fuera del dispatcher de Spring MVC, igual que el `authenticationEntryPoint`. El manejo de ese error es responsabilidad exclusiva de la Task 7, no se puede asumir que un `@RestControllerAdvice` lo va a capturar.

- [x] Task 6: `application/usecase/FederatedLoginCommand` + `FederatedLoginUseCase` (AC: #1, #2, #3)
  - [x] `record FederatedLoginCommand(String provider, String providerUserId, String rawEmail, boolean emailVerified)` — mismo patrón que `GetOwnProfileCommand`/`LoginCommand`: datos crudos, el parseo/validación a Value Objects ocurre dentro del caso de uso, nunca en el adapter de infraestructura que lo construye.
  - [x] `domain/exception/FederatedLoginFailedException.java` — Java puro, mismo patrón mínimo que `AuthenticationFailedException`/`AccountNotFoundException` (constructor con mensaje, sin lógica). **No** reutilizar `AuthenticationFailedException` (su mensaje está fijado a `"Email o contraseña incorrectos."`, semánticamente incorrecto aquí — no hubo contraseña).
  - [x] `FederatedLoginUseCase`, `@Service`, `@Transactional` (muta estado: puede crear Cuenta + Identidad Federada, a diferencia de `GetOwnProfileUseCase` que es de solo lectura), constructor-injected con `AccountRepository`, `FederatedIdentityRepository`, `TokenIssuer`, `Clock`.
  - [x] Método `TokenIssuer.IssuedTokens login(FederatedLoginCommand command)`:
    1. `FederatedProvider provider = FederatedProvider.valueOf(command.provider().toUpperCase())` — capturar `IllegalArgumentException` (proveedor no registrado) y relanzar `FederatedLoginFailedException`, nunca dejar escapar la excepción cruda (mismo principio que `GetOwnProfileUseCase` con `UUID.fromString`).
    2. Si `!command.emailVerified()`, lanzar `FederatedLoginFailedException("Email no verificado por el proveedor.")` de inmediato — **antes** de tocar cualquier repositorio. Vincular una Cuenta a un email que el proveedor no verificó es un vector de account takeover (un atacante podría reclamar el email de otra persona); no es un AC explícito del épico, pero es la única lectura segura de AC #2 ("email verificado por Google") — **[ASSUMPTION]**, documentar en Dev Notes.
    3. `Email email; try { email = new Email(command.rawEmail()); } catch (DomainValidationException e) { throw new FederatedLoginFailedException(...); }`.
    4. `federatedIdentityRepository.findByProviderAndProviderUserId(provider, command.providerUserId())` → si presente, resolver `accountRepository.findById(identity.accountId())` (Cuenta ya vinculada, login recurrente) y saltar directo al paso 6.
    5. Si no hay Identidad Federada previa: `accountRepository.findByEmail(email)`:
       - Presente → **AC #2**: usar esa Cuenta, crear `FederatedIdentity.link(account.id(), provider, command.providerUserId(), clock)`, `federatedIdentityRepository.save(...)`.
       - Ausente → **AC #1**: `Account account = Account.registerFederated(email); accountRepository.save(account);` luego crear y guardar la `FederatedIdentity` de la misma forma.
    6. `return tokenIssuer.issue(account);` — **reutilizar tal cual**, no reconstruir la emisión de tokens (AD-2, el propio Javadoc de `TokenIssuer` ya anticipa este caso: *"para login (Story 1.4) y, más adelante, login federado (Epic 2)"*).
  - [x] Tests unitarios con mocks de los 3 puertos: (a) visitante nuevo sin Cuenta ni Identidad → crea Cuenta `ACTIVE`/`USER`/sin password + Identidad, emite tokens; (b) Identidad Federada ya existente → resuelve la misma Cuenta sin crear duplicado, emite tokens; (c) sin Identidad previa pero email coincide con Cuenta existente → vincula sin duplicar Cuenta, emite tokens; (d) `emailVerified=false` → `FederatedLoginFailedException`, ningún repositorio de escritura invocado, `tokenIssuer` nunca invocado; (e) proveedor no reconocido → `FederatedLoginFailedException` sin propagar `IllegalArgumentException` cruda.

- [x] Task 7: `infrastructure/adapters/oauth/` — success/failure handlers (AC: #1, #2, #3)
  - [x] `OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler`: extrae de la `Authentication` (`OAuth2AuthenticationToken` envolviendo un `OidcUser` para Google) el `registrationId` (→ `provider`), el claim `sub` (→ `providerUserId`), `email` y `email_verified`; construye `FederatedLoginCommand` y llama a `federatedLoginUseCase.login(...)`; en éxito, redirige el navegador a `oAuth2RedirectProperties.successRedirectUri()` con el Access+Refresh Token como query params de corta vida (patrón estándar para backends API-only sin login UI propia — ver Dev Notes).
  - [x] `OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler`: cubre el fallo/cancelación **en el propio proveedor** (antes de llegar a nuestro `FederatedLoginUseCase` — p. ej. el visitante deniega el consentimiento en Google) — redirige a `oAuth2RedirectProperties.failureRedirectUri()` con un código de error genérico en query param, nunca el detalle de la excepción.
  - [x] Dentro de `OAuth2AuthenticationSuccessHandler`, capturar `FederatedLoginFailedException` (el fallo interno del paso 2 de la Task 6, `emailVerified=false`, etc.) y redirigir por el **mismo** `failureRedirectUri` que `OAuth2AuthenticationFailureHandler` — un solo contrato de fallo hacia el cliente, sin bifurcar entre "falló en Google" vs "falló en nuestra validación" (AD-8, mismo principio de indistinguibilidad ya aplicado en `GlobalExceptionHandler`/`SecurityConfig`).
  - [x] `config/OAuth2RedirectProperties.java` — `@ConfigurationProperties(prefix = "auth.oauth2")`, `record` o clase con `successRedirectUri`/`failureRedirectUri` (NFR-7: nada hardcodeado), mismo patrón que `JwtProperties`/`AuthTokenProperties` ya existentes.
  - [x] Tests: invocar los handlers directamente construyendo un `OAuth2AuthenticationToken`/`DefaultOidcUser` a mano (no requiere simular la redirección real a Google — el intercambio del código y el `state`/PKCE ya están cubiertos por el propio framework de Spring Security, probado upstream) — mockear `FederatedLoginUseCase`, verificar la redirección construida (URI base + query params) en éxito y en cada camino de fallo.

- [x] Task 8: Configuración — `ClientRegistration` de Google + variables de entorno (AC: #1)
  - [x] `application.properties`: añadir bajo una nueva sección `OAUTH2 (AD-17, Epic 2)`:
    ```properties
    spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
    spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}
    spring.security.oauth2.client.registration.google.scope=openid,email,profile

    auth.oauth2.success-redirect-uri=${OAUTH2_SUCCESS_REDIRECT_URI}
    auth.oauth2.failure-redirect-uri=${OAUTH2_FAILURE_REDIRECT_URI}
    ```
    `spring.security.oauth2.client.provider.google` no hace falta declararlo — Spring Boot ya reconoce `google` como `CommonOAuth2Provider` con todos los endpoints/`issuer-uri` preconfigurados.
  - [x] `.env.example`: `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` **ya existen** (confirmado, sin tocar); añadir `OAUTH2_SUCCESS_REDIRECT_URI=` y `OAUTH2_FAILURE_REDIRECT_URI=`.
  - [x] **No** configurar GitHub en esta historia — `spring.security.oauth2.client.registration.github.*` es explícitamente Story 2.2, aunque el `.env.example` ya declare esas variables por adelantado.

- [x] Task 9: Exponer Identidades Federadas reales en `GET /api/v1/users/me` (AC: #1, #2 — cierre de la Story 1.7)
  - [x] La Story 1.7 dejó `ProfileResponse.federatedIdentities` hardcodeado a `List.of()` **a propósito**, documentado como correcto *"mientras Epic 2 no exista"* — esa condición ya se cumple con esta historia; dejarlo vacío para siempre convertiría el campo en información falsa para una Cuenta ya vinculada a Google.
  - [x] `GetOwnProfileUseCase` gana una dependencia nueva: `FederatedIdentityRepository` (constructor). Añade `federatedIdentityRepository.findByAccountId(account.id())` y expone la lista de `provider().name()` (minúsculas, p. ej. `"google"`) — la forma exacta de string la decide `UserController.toResponse`, no el caso de uso (mismo principio de Story 1.7: el caso de uso devuelve el agregado/datos crudos, el controller mapea a DTO).
  - [x] `UserController.toResponse` deja de hardcodear `List.of()`; mapea la lista real. Cuenta sin identidades vinculadas sigue devolviendo lista vacía (comportamiento ya cubierto, no cambia).
  - [x] Actualizar `GetOwnProfileUseCaseTest`, `UserControllerTest`, `UserProfileIntegrationTest`: añadir un caso con una Cuenta que tiene una `FederatedIdentity` vinculada → `federatedIdentities` no vacío con el proveedor esperado; mantener el caso existente de lista vacía para una Cuenta sin vínculos.

- [x] Task 10: Tests de integración (AC: #1, #2, #3)
  - [x] `GET /oauth2/authorization/google` sin autenticar → redirección (3xx) hacia el endpoint de autorización de Google (`accounts.google.com/...`), **nunca** `401` — confirma que `/oauth2/**` quedó público en `PUBLIC_ENDPOINTS`.
  - [x] No es posible ni deseable invocar la API real de Google en tests — el intercambio código→token y la obtención del `OidcUser` son responsabilidad ya probada de Spring Security; el valor de la integración de esta historia está en `FederatedLoginUseCase` contra PostgreSQL real (Testcontainers, mismo patrón que `AuthLoginIntegrationTest`): invocar el caso de uso directamente con un `FederatedLoginCommand` de prueba (simulando lo que el success handler ya extrajo) y verificar que la Cuenta/Identidad Federada quedan persistidas correctamente y que el Access Token resultante pasa por `JwtAuthenticationFilter` en `GET /api/v1/users/me` exactamente igual que uno emitido por `/auth/login`.
  - [x] Caso AC #2 en integración: persistir una Cuenta local (`AccountRepository`, con password) y luego invocar `FederatedLoginUseCase` con el mismo email → verificar que sigue existiendo una sola fila en `accounts` y que la `FederatedIdentity` nueva apunta a esa Cuenta.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Stories 1.1-1.7 — prestar particular atención a `LoginUseCaseTest`/`AuthLoginIntegrationTest` tras el cambio de la Task 3.

### Review Findings

<!-- Code review 2026-07-14 — capas: Blind Hunter, Edge Case Hunter, Acceptance Auditor. 1 decision-needed, 6 patch, 4 defer, 3 dismissed como ruido. -->

- [x] [Review][Decision] Access+Refresh Token viajan como query params en texto plano en la redirección de éxito — quedan en el historial del navegador, en logs de servidor/proxy/CDN, y se filtran vía `Referer` a cualquier recurso de terceros que cargue la página de destino. Los propios Dev Notes de esta historia ya marcan el contrato de redirección como `[ASSUMPTION]` pendiente de confirmar con producto. Confirmado independientemente por Blind Hunter, Edge Case Hunter y Acceptance Auditor. **Resuelto (2026-07-14): código de intercambio de un solo uso.** El redirect ahora lleva solo `?code=<opaco>`; el frontend lo canjea vía `POST /auth/oauth2/exchange` por el par real. Implementado: `domain/port/OAuth2ExchangeCodeStore` (puerto), `InMemoryOAuth2ExchangeCodeStore` (adapter, TTL 60s, un solo uso, purga oportunista de expirados), `OAuth2ExchangeFailedException`, `OAuth2ExchangeCommand`/`OAuth2ExchangeUseCase`, endpoint nuevo en `AuthController`. [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandler.java:229-236]

- [ ] [Review][Patch] `FederatedLoginUseCase.login()` nunca valida `Account.status()` antes de emitir tokens — a diferencia de `LoginUseCase`, una Cuenta `LOCKED` (p. ej. bloqueada por fuerza bruta) o `DISABLED` puede autenticarse vía Google y recibir tokens válidos, saltándose por completo el lockout/disable que el resto del servicio ya hace cumplir (NFR-2/AD-8). Confirmado independientemente por los tres reviewers. [src/main/java/com/auth_service/auth/application/usecase/FederatedLoginUseCase.java:82]
- [ ] [Review][Patch] `OAuth2AuthenticationSuccessHandler` solo captura `FederatedLoginFailedException` — cualquier otra excepción (violación de constraint único por carrera en el alta concurrente de una misma Identidad/email nuevos, un `Authentication` que no sea `OAuth2AuthenticationToken`, cualquier fallo de persistencia) escapa sin capturar, produciendo un 500 crudo en vez de la redirección de fallo controlada que exige AC #3. [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandler.java:53,80]
- [ ] [Review][Patch] `CookieOAuth2AuthorizationRequestRepository.deserialize()` no captura excepciones ante una cookie corrupta o manipulada (Base64 inválido, clase deserializada válida pero incorrecta) — `IllegalArgumentException`/`ClassCastException` sin capturar convierten el callback de OAuth2 en un 500 en vez de simplemente reiniciar el flujo. [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java:105]
- [ ] [Review][Patch] `AllowListObjectInputStream.resolveClass()` permite cualquier tipo array (`desc.getName().startsWith("[")`) sin validar el tipo de componente contra la lista blanca — debilita la protección contra deserialización insegura (CWE-502) que la propia lista blanca busca dar. [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java:125]
- [ ] [Review][Patch] La cookie de la Authorization Request no fija el atributo `SameSite` — el patrón externo que Dev Notes cita como ya resuelto normalmente lo fija explícitamente; dejarlo al default del navegador es una desviación real, aunque de severidad menor (el default `Lax` sigue permitiendo la redirección top-level desde Google). [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java:69]
- [ ] [Review][Patch] `OAuth2RedirectProperties` no valida al arranque que `successRedirectUri`/`failureRedirectUri` sean URIs sintácticamente válidas — un valor mal configurado solo falla en el primer login real en vez de al levantar el servicio, inconsistente con el fail-fast que ya aplican `JwtProperties`/`AuthTokenProperties`. [src/main/java/com/auth_service/auth/config/OAuth2RedirectProperties.java:12]

- [x] [Review][Defer] El flag `Secure` de la cookie depende directamente de `request.isSecure()` — detrás de un proxy/balanceador que termine TLS sin `server.forward-headers-strategy` configurado, puede degradar silenciosamente a insegura en producción. Deferred: depende de la topología de despliegue, no del código en sí. [src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java]
- [x] [Review][Defer] El payload de la cookie de autorización no lleva firma/HMAC — es tamperable client-side. Deferred: la protección CSRF real del flujo OAuth2 la da el parámetro `state` (validado por Spring Security en el callback), no la cookie; firmar el payload es defensa en profundidad, no una vulnerabilidad explotable de forma directa hoy.
- [x] [Review][Defer] `COOKIE_MAX_AGE_SECONDS` hardcodeado a 180s sin propiedad de configuración — coincide con el valor que la propia historia sugiere ("TTL corto, p. ej. 3 minutos"); deferred, revisar si aparecen quejas reales de usuarios que tardan más en el consentimiento de Google.
- [x] [Review][Defer] La cookie de Authorization Request podría exceder el límite práctico de ~4KB de un navegador en casos extremos, sin guarda explícita — deferred, el payload (state + PKCE + redirect URI) es normalmente pequeño; baja probabilidad.

**Descartados como ruido (verificados contra el código y el spec real, Blind Hunter no tiene acceso al repo):**
- "Vincular una Identidad Federada a una Cuenta existente solo por coincidencia de email, sin re-autenticación" — la mitigación ya existe: el guard `emailVerified` (Task 6, paso 2) rechaza el login *antes* de tocar cualquier repositorio si Google no verificó el email — exactamente la razón por la que esa historia documenta ese chequeo como `[ASSUMPTION]` obligatoria. Confiar en el `email_verified` del proveedor es el modelo estándar de la industria para login federado, no un descuido de esta implementación.
- "`FederatedProvider.GITHUB` es aceptado por `valueOf` aunque no exista `ClientRegistration` de GitHub" — inalcanzable por el flujo real: Spring Security nunca produce un `OAuth2AuthenticationToken` con `registrationId="github"` sin un `ClientRegistration` registrado, así que no hay ruta HTTP real que llegue a ese `provider`. El enum se declara ahora a propósito para que la Story 2.2 lo reutilice (documentado en Dev Notes), no es una superficie de validación abierta de facto.
- "`GoogleLoginIntegrationTest` no ejercita el callback real `/login/oauth2/code/google` ni el wiring completo de `oauth2Login()`" — ya justificado explícitamente en la Task 10: simular el intercambio código→token de Google no es posible ni deseable en tests; esa responsabilidad ya está probada upstream por Spring Security.

## Dev Notes

- **Todo lo que ya existe y debe reutilizarse, no reconstruirse:** `spring-boot-starter-oauth2-client` y `resilience4j-spring-boot3` ya están en `pom.xml` desde la Story 1.1 (sin usar hasta ahora); `TokenIssuer.issue(account)` ya es genérico y su propio Javadoc anticipa este caso; `AccountRepositoryAdapter` ya maneja `passwordHash == null` en ambas direcciones; `accounts.password_hash` ya es nullable desde `V2__accounts.sql`; `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` ya están en `.env.example` y documentados en `README.md`. Esta historia **conecta** piezas ya preparadas, no parte de cero en infraestructura.
- **`/oauth2/authorization/google` es la ruta correcta de Spring Security** (prefijo por defecto `/oauth2/authorization/{registrationId}`, confirmado por los AC de epics.md) — el `README.md` original del proyecto menciona `GET /oauth2/google`, que es informal/desactualizado (ese mismo README también usa `jwt.secret-key` en vez de las propiedades reales `auth.jwt.secret-current`); no seguir esa tabla de endpoints al pie de la letra.
- **STATELESS + `oauth2Login()` es la única fricción arquitectónica real de esta historia:** el flujo estándar de Spring Security guarda el `OAuth2AuthorizationRequest` en `HttpSession` entre la redirección y el callback, lo cual choca con AD-3 (todo el servicio es `STATELESS`, sin excepciones hasta ahora). La solución (Task 5) es un `AuthorizationRequestRepository` propio respaldado por cookie en vez de sesión — patrón externo bien establecido, no una invención de esta historia. Ver Sources abajo.
- **Sin UI propia de login (Brief, alcance del MVP):** el success/failure handler no puede terminar en una página HTML propia — termina en una redirección del navegador a una URL de frontend configurable (`auth.oauth2.success-redirect-uri`/`failure-redirect-uri`, NFR-7) con los tokens (éxito) o un código de error genérico (fallo) como query params. La forma exacta del contrato de redirección **no está especificada por epics.md/PRD** — es la interpretación más consistente con "API-first, sin UI propia" y con el patrón estándar de la industria para backends OAuth2 stateless sin página de login server-side. **[ASSUMPTION]** — si el equipo de producto ya tiene una URL de frontend real y un contrato de query params distinto, ajustar antes de implementar; el mecanismo (handler + property configurable) no cambia.
- **`email_verified = false` como fallo controlado (AC #3), no como vinculación silenciosa:** ni epics.md ni el PRD mencionan explícitamente el chequeo de `email_verified`, pero omitirlo abre un vector de account takeover (vincular una Identidad Federada a la Cuenta de otra persona reclamando su email sin haberlo verificado). Se trata como parte de "fallo/cancelación... error controlado" del AC #3. **[ASSUMPTION]**, documentada explícitamente para que un revisor humano pueda confirmar o corregir el criterio.
- **Regresión real en `LoginUseCase` (Task 3), no un caso hipotético:** en cuanto exista la primera Cuenta federada-only (esta misma historia la crea), `passwordHasher.matches(raw, null)` truena con `NullPointerException` no capturada → `500` en vez del `401` genérico esperado por NFR-2/AD-8. Corregirla es parte obligatoria de esta historia, no un ítem separado de code review.
- **Resiliencia (NFR-12/AD-17) formalmente es Story 5.2, no bloquea esta historia:** el AC de Story 5.2 en epics.md referencia literalmente "Story 2.1" como prerequisito (5.2 depende de 2.1, no al revés). El `CircuitBreaker`/`TimeLimiter` de Resilience4j sobre la llamada a Google se instrumenta formalmente en 5.2; esta historia no necesita envolver la llamada OAuth2 con Resilience4j para cumplir sus propios AC (aunque la dependencia ya esté en el `pom.xml` desde 1.1).
- **`FederatedIdentity` es un agregado propio, no un Value Object anidado en `Account`:** mismo patrón que `RefreshToken` (no vive dentro de `Account`, tiene su propio repositorio) — evita que `Account` cargue una colección de identidades en cada `findById`/`findByEmail` cuando la mayoría de los casos de uso no la necesitan (Y/AGNI, mismo criterio que llevó a separar `RefreshToken`).
- **Story 2.2 (GitHub, `backlog`) reutiliza esta infraestructura sin tocarla:** `FederatedProvider.GITHUB` ya se declara en esta historia; `FederatedLoginUseCase`, la tabla `federated_identities` y los handlers son genéricos por diseño — 2.2 solo añade el `ClientRegistration` de GitHub y, si aplica, un mapeo de claims específico del userinfo de GitHub (que no expone `email_verified` de la misma forma que Google — quedará para cuando se cree esa historia, no se resuelve aquí).

### Project Structure Notes

- **Nuevos paquetes:** `infrastructure/adapters/oauth/` (`CookieOAuth2AuthorizationRequestRepository`, `OAuth2AuthenticationSuccessHandler`, `OAuth2AuthenticationFailureHandler`) — ya anticipado por el Structural Seed del Architecture Spine (`adapters/oauth/ # OAuth2 success handler → application/usecase`).
- **Nuevos en `domain/model/`:** `FederatedProvider` (enum), `FederatedIdentity`.
- **Nuevo en `domain/exception/`:** `FederatedLoginFailedException`.
- **Nuevos en `domain/port/`:** `FederatedIdentityRepository`.
- **Nuevos en `application/usecase/`:** `FederatedLoginCommand`, `FederatedLoginUseCase`.
- **Nuevos en `infrastructure/adapters/postgresql/`:** `FederatedIdentityEntity`, `FederatedIdentityJpaRepository`, `FederatedIdentityRepositoryAdapter`.
- **Nuevo en `config/`:** `OAuth2RedirectProperties`.
- **Nueva migración:** `src/main/resources/db/migration/V4__federated_identities.sql`.
- **Modificados:** `Account.java` (nuevo factory `registerFederated`), `LoginUseCase.java` (guard contra `passwordHash == null`, corrección de regresión), `SecurityConfig.java` (`PUBLIC_ENDPOINTS` + `.oauth2Login(...)`), `GetOwnProfileUseCase.java`/`UserController.java` (Task 9, exponer identidades reales), `application.properties`, `.env.example`.
- **Sin cambios:** `AccountRepositoryAdapter` (ya tolera `passwordHash == null`), `TokenIssuer` (ya genérico), `JwtAuthenticationFilter` (agnóstico del método de login), `GlobalExceptionHandler` (los errores de esta historia no pasan por ahí — ver Task 5/7).

### References

- [Source: docs/planning-artifacts/epics.md#Epic-2] — objetivo del épico, Story 2.1 completa (user story + AC Given/When/Then), relación con Story 2.2, FR-6
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-6] — "Autenticación con Google y GitHub", consecuencias testables, UJ-2
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#NFR-2,NFR-7,NFR-12] — anti-enumeración, configuración por entorno, resiliencia ante proveedores externos
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-2] — punto único de emisión de tokens, vinculante para el success handler
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-3] — claims fijas del Access Token, mismo shape para login federado
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-8] — Problem Details + anti-enumeración
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-11] — deny-all, `/oauth2/**` ya anticipado como ruta pública futura
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso como frontera transaccional
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-17] — resiliencia ante proveedores externos, formalmente Story 5.2 (referencia explícita a esta Story 2.1 como prerequisito)
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Structural-Seed] — diagrama ER de `FEDERATED_IDENTITY`, paquete `adapters/oauth/`
- [Source: docs/planning-artifacts/briefs/brief-auth-service-2026-07-01/brief.md] — justificación de negocio de login social, "sin UI propia de login" como límite de alcance del MVP
- [Source: docs/implementation-artifacts/1-7-perfil-propio.md] — `ProfileResponse.federatedIdentities` hardcodeado a `List.of()` a la espera de esta historia (Task 9); patrón Command crudo + parseo dentro del caso de uso
- [Source: src/main/java/com/auth_service/auth/domain/model/Account.java] — forma actual del aggregate, constructor privado ya tolera `passwordHash == null`
- [Source: src/main/java/com/auth_service/auth/domain/model/HashedPassword.java] — valida no-nulidad solo en su propio constructor, no en el de `Account`
- [Source: src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java] — regresión identificada en `passwordHasher.matches(...)` con `passwordHash == null`
- [Source: src/main/java/com/auth_service/auth/application/usecase/TokenIssuer.java] — punto único de emisión, ya genérico, Javadoc anticipa login federado
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AccountRepositoryAdapter.java] — mapeo `passwordHash` nulo ya implementado en ambas direcciones
- [Source: src/main/java/com/auth_service/auth/config/SecurityConfig.java] — `PUBLIC_ENDPOINTS`, deny-all, Javadoc anticipa `/oauth2/**`
- [Source: src/main/java/com/auth_service/auth/domain/model/RefreshToken.java] — patrón a replicar para `FederatedIdentity` (agregado propio, no anidado en `Account`)
- [Source: pom.xml] — `spring-boot-starter-oauth2-client` y `resilience4j-spring-boot3` ya presentes desde Story 1.1
- [Source: README.md, .env.example] — contrato de variables de entorno `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` ya declarado; ruta de endpoint en README es informal, no autoritativa
- [Source externo: "Stateless OAuth2 Social Logins with Spring Boot", jessym.com] — patrón `AuthorizationRequestRepository` respaldado por cookie para mantener `SessionCreationPolicy.STATELESS` con `oauth2Login()`
- [Source externo: Spring Security Reference, "Advanced Configuration" (OAuth2 Login)] — configuración de `authorizationRequestRepository`, success/failure handlers personalizados

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -o test -Dtest=LoginUseCaseTest` — test de regresión (`passwordHash == null`) confirmado en rojo (NPE no capturado por el mock de `passwordHasher`, ajustado a verificar `never().matches(...)`) antes de aplicar el guard en `LoginUseCase`; 7/7 en verde después.
- `./mvnw -o test -Dtest=CookieOAuth2AuthorizationRequestRepositoryTest` — el primer compile con `SerializationUtils.deserialize(byte[])` mostró warning de deprecación (Spring); reemplazado por un `ObjectInputStream` con allow-list de clases (endurecimiento de seguridad no pedido explícitamente por la historia pero necesario: el cookie es dato de cliente no confiable — CWE-502). 6/6 en verde, incluidos dos tests de cookie manipulada.
- `./mvnw -o test -Dtest=SecurityConfigTest,AuthControllerTest,UserControllerTest` — tras cablear `.oauth2Login(...)` en `SecurityConfig`, los tres slice tests que importan `SecurityConfig.class` requirieron `@MockitoBean` para los 3 colaboradores OAuth2 nuevos + propiedades de `ClientRegistration` de prueba; 18/18 en verde.
- `./mvnw -o test -Dtest=RefreshTokenRepositoryAdapterTest` — regresión real detectada: con `webEnvironment=NONE`, Spring Boot no autoconfigura `ClientRegistrationRepository` (condicional a aplicación web), pero `SecurityConfig` sigue cargándose y `.oauth2Login()` lo exige → `APPLICATION FAILED TO START`. Corregido con `@MockitoBean ClientRegistrationRepository`. 5/5 en verde tras el fix.
- `./mvnw -o test` (suite completa, sesión de desarrollo, Docker disponible) — 180 tests, 0 failures, 0 errors, 0 skipped tras Tasks 1-8.
- `./mvnw -o test -Dtest=GoogleLoginIntegrationTest` — 3/3 en verde contra PostgreSQL real: redirección pública a Google, alta de Cuenta nueva + Identidad Federada, vinculación sin duplicar Cuenta existente.
- `./mvnw -B verify` final (suite completa + Task 9) — **186 tests, 0 failures, 0 errors, 0 skipped**. JaCoCo `check` en verde. `ArchitectureRulesTest` 3/3 en verde (las clases nuevas de dominio siguen sin dependencias de Spring/JPA). `BUILD SUCCESS`.

### Completion Notes List

- Las 3 AC están implementadas: alta de Cuenta `ACTIVE` sin password + Identidad Federada nueva (AC #1); vinculación a Cuenta local existente sin duplicarla, verificado también que logins recurrentes por la misma Identidad Federada resuelven la misma Cuenta (AC #2); `email_verified=false`, proveedor no reconocido, email malformado y fallos del propio proveedor terminan todos en el mismo contrato de error controlado sin estado parcial (AC #3).
- Regresión pre-existente corregida como parte obligatoria de esta historia: `LoginUseCase` ahora rechaza con el mismo `401` genérico un intento de login por password sobre una Cuenta federada-only, en vez de un `NullPointerException` no capturado (`500`).
- Endurecimiento de seguridad no solicitado explícitamente pero necesario: la deserialización del cookie de `AuthorizationRequestRepository` usa una allow-list de clases en vez del `SerializationUtils.deserialize(byte[])` deprecado de Spring, cerrando un vector de deserialización insegura (CWE-502) sobre un dato que el cliente controla por completo.
- Cierre de un cabo suelto documentado en la Story 1.7: `GET /api/v1/users/me` ya expone las Identidades Federadas reales de la Cuenta (antes hardcodeado a lista vacía a propósito, mientras Epic 2 no existiera).
- Dos decisiones de diseño no especificadas literalmente por epics.md/PRD, documentadas como `[ASSUMPTION]` en Dev Notes: rechazo de `email_verified=false` como vector de account takeover, y el contrato de redirección del navegador (URLs de frontend configurables vía `auth.oauth2.success-redirect-uri`/`failure-redirect-uri`, sin UI propia de login).
- Se detectó y corrigió una segunda regresión durante la implementación, no anticipada por la historia: con `@SpringBootTest(webEnvironment = NONE)` (`RefreshTokenRepositoryAdapterTest`), Spring Boot no autoconfigura `ClientRegistrationRepository`, pero `SecurityConfig.oauth2Login()` lo exige igual — resuelto con un `@MockitoBean` en ese test, sin tocar el código de producción.
- Docker estuvo disponible durante toda la sesión de desarrollo — todos los tests de integración (Testcontainers) corrieron en verde, sin ningún `skipped`.

### File List

**Nuevos:**
- `src/main/resources/db/migration/V4__federated_identities.sql`
- `src/main/java/com/auth_service/auth/domain/model/FederatedProvider.java`
- `src/main/java/com/auth_service/auth/domain/model/FederatedIdentity.java`
- `src/main/java/com/auth_service/auth/domain/port/FederatedIdentityRepository.java`
- `src/main/java/com/auth_service/auth/domain/exception/FederatedLoginFailedException.java`
- `src/main/java/com/auth_service/auth/application/usecase/FederatedLoginCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/FederatedLoginUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/FederatedIdentityEntity.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/FederatedIdentityJpaRepository.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/FederatedIdentityRepositoryAdapter.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepository.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandler.java`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationFailureHandler.java`
- `src/main/java/com/auth_service/auth/config/OAuth2RedirectProperties.java`
- `src/test/java/com/auth_service/auth/domain/model/FederatedIdentityTest.java`
- `src/test/java/com/auth_service/auth/application/usecase/FederatedLoginUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/CookieOAuth2AuthorizationRequestRepositoryTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandlerTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationFailureHandlerTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/GoogleLoginIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/model/Account.java` (nuevo factory `registerFederated`)
- `src/main/java/com/auth_service/auth/application/usecase/LoginUseCase.java` (guard contra `passwordHash == null`, corrección de regresión)
- `src/main/java/com/auth_service/auth/application/usecase/GetOwnProfileUseCase.java` (nueva dependencia `FederatedIdentityRepository`, retorna `OwnProfile`)
- `src/main/java/com/auth_service/auth/infrastructure/controller/UserController.java` (expone `federatedIdentities` reales)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (`PUBLIC_ENDPOINTS` + `.oauth2Login(...)`)
- `src/main/resources/application.properties` (sección OAUTH2)
- `.env.example` (`OAUTH2_SUCCESS_REDIRECT_URI`, `OAUTH2_FAILURE_REDIRECT_URI`)
- `src/test/java/com/auth_service/auth/domain/model/AccountTest.java` (tests de `registerFederated`)
- `src/test/java/com/auth_service/auth/application/usecase/LoginUseCaseTest.java` (test de regresión federada-only)
- `src/test/java/com/auth_service/auth/application/usecase/GetOwnProfileUseCaseTest.java` (`OwnProfile`, casos de Identidad Federada)
- `src/test/java/com/auth_service/auth/infrastructure/controller/UserControllerTest.java` (`OwnProfile`, mocks OAuth2, caso de Identidad Federada)
- `src/test/java/com/auth_service/auth/infrastructure/controller/UserProfileIntegrationTest.java` (caso de integración con Identidad Federada real)
- `src/test/java/com/auth_service/auth/config/SecurityConfigTest.java` (mocks OAuth2, test de ruta pública `/oauth2/**`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (mocks OAuth2)
- `src/test/java/com/auth_service/auth/infrastructure/adapters/postgresql/RefreshTokenRepositoryAdapterTest.java` (mock de `ClientRegistrationRepository`)

## Change Log

| Fecha | Cambio |
|---|---|
| 2026-07-13 | Creación de la Story 2.1 a partir de epics.md (Epic 2), PRD FR-6, ARCHITECTURE-SPINE.md, brief.md, continuidad con Story 1.7, e investigación externa sobre OAuth2 stateless en Spring Security. |
| 2026-07-13 | Implementación de la Story 2.1 (Tasks 1-10): login federado con Google completo — migración, dominio, persistencia, `SecurityConfig` stateless con OAuth2, casos de uso, handlers, configuración, exposición de Identidades Federadas en el perfil propio, y tests unitarios/slice/integración. Corregidas dos regresiones detectadas durante la implementación (login por password sobre Cuenta federada-only; arranque de `RefreshTokenRepositoryAdapterTest` con `webEnvironment=NONE`). |
