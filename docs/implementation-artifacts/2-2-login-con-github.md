---
baseline_commit: b3c29b8a097e5d016eca43db29226a4285584c14
---

# Story 2.2: Login con GitHub

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a visitante,
I want continuar con mi cuenta de GitHub,
so that acceda con la identidad que ya uso como desarrollador. (FR-6)

## Acceptance Criteria

1. Dado un visitante sin Cuenta previa, al completar con éxito el flujo `/oauth2/authorization/github`, `FederatedLoginUseCase` crea una Cuenta `ACTIVE` con Rol `USER`, sin contraseña local, con una Identidad Federada `github` persistida en `federated_identities` — reutilizando **sin modificar** el mismo `FederatedLoginUseCase`, `TokenIssuer`, migración y tabla de la Story 2.1 (AD-2, AD-13); el visitante recibe el mismo par Access+Refresh, mismo shape de claims, que el login con credenciales o con Google.
2. Dado una Cuenta local o federada-Google existente cuyo email coincide con un email **primario y verificado** de la cuenta de GitHub del visitante, al completar el login con GitHub la Identidad Federada `github` se vincula a esa Cuenta existente sin duplicarla — logins posteriores por credenciales, Google o GitHub acceden a la misma Cuenta (no se crea una segunda fila en `federated_identities` para el mismo `(provider, provider_user_id)`).
3. **GitHub no expone `email_verified` en el recurso `/user` como lo hace el `id_token` OIDC de Google** — la única fuente confiable de "email primario verificado" es `GET https://api.github.com/user/emails` (requiere scope `user:email`). Dado un visitante cuyo email primario en GitHub **no** está verificado, o que no concede el scope `user:email`, o cuya llamada a ese endpoint falla, el login se rechaza como `FederatedLoginFailedException` (mismo contrato que `emailVerified=false` en Google, Story 2.1) — nunca se vincula ni se crea una Cuenta con un email sin verificar.
4. Dado un fallo o cancelación del flujo en GitHub (denegación de consentimiento, error en el intercambio del código, fallo de red al consultar `/user/emails`), al retornar el flujo con error no se crea ningún estado parcial (ninguna fila huérfana en `accounts` ni en `federated_identities`) y el visitante recibe un error controlado por el mismo `OAuth2AuthenticationFailureHandler` de la Story 2.1 (AD-8) — nunca un stack trace ni un `500`.
5. Dado un titular con Cuenta creada solo por vía federada (Google o GitHub, sin contraseña local), su perfil (`GET /api/v1/users/me`, Story 1.7/2.1) expone ambas Identidades Federadas si vinculó las dos — sin cambios de código adicionales, ya cubierto genéricamente por la Task 9 de la Story 2.1.

## Tasks / Subtasks

- [x] Task 1: Configuración — `ClientRegistration` de GitHub + variables de entorno (AC: #1, #3)
  - [x] `application.properties`, bajo la sección `OAUTH2` ya existente (creada en Story 2.1):
    ```properties
    spring.security.oauth2.client.registration.github.client-id=${GITHUB_CLIENT_ID}
    spring.security.oauth2.client.registration.github.client-secret=${GITHUB_CLIENT_SECRET}
    spring.security.oauth2.client.registration.github.scope=read:user,user:email
    ```
    `spring.security.oauth2.client.provider.github` no hace falta declararlo — Spring Boot ya reconoce `github` como `CommonOAuth2Provider` (endpoints de autorización/token/userinfo preconfigurados, `user-name-attribute=id`). El scope por defecto de `CommonOAuth2Provider.GITHUB` es solo `read:user` — **no** incluye `user:email`, imprescindible para poder llamar a `/user/emails` (Task 2); se sobreescribe explícitamente.
  - [x] `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` **ya existen** en `.env.example` desde la Story 2.1 (declaradas por adelantado, sin usar hasta ahora) — no tocar ese archivo.
  - [x] `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` ya cubren ambos proveedores (`OAuth2RedirectProperties` es agnóstico del proveedor) — no se duplican por proveedor.

- [x] Task 2: `infrastructure/adapters/oauth/GitHubOAuth2UserService` — resolver el email primario verificado (AC: #1, #3, #4)
  - [x] Implementa `OAuth2UserService<OAuth2UserRequest, OAuth2User>` (el contrato **no-OIDC** — GitHub no es un proveedor OIDC, a diferencia de Google; por eso Google usa `oidcUserService` con `OidcUser`/`email_verified` nativo del `id_token`, y GitHub necesita esta pieza nueva).
  - [x] Delega en una instancia interna de `DefaultOAuth2UserService` para obtener el `OAuth2User` base (llamada estándar a `GET https://api.github.com/user`, atributos incluyendo `id` — ya configurado como `user-name-attribute` por `CommonOAuth2Provider.GITHUB`, entiéndase `principal.getName()` devuelve ese `id` sin cambios).
  - [x] Solo si `userRequest.getClientRegistration().getRegistrationId().equals("github")`: llamar `GET https://api.github.com/user/emails` con header `Authorization: Bearer <access_token>` (usar `userRequest.getAccessToken().getTokenValue()`) y `Accept: application/vnd.github+json` — respuesta: array de objetos `{email, primary, verified, visibility}` ([Source externo: GitHub REST API docs, "Emails" — confirmado por fetch directo 2026-07-15]). Buscar el elemento con `primary=true && verified=true`; si no existe ninguno, `email = null` (no lanzar excepción aquí — el rechazo ocurre uniformemente en `FederatedLoginUseCase` vía el guard `emailVerified`, Task 3).
  - [x] Construir un nuevo `DefaultOAuth2User` que **añade** dos atributos sintéticos al mapa original del `OAuth2User` base: `email` (el email primario verificado encontrado, o `null`) y `email_verified` (`true` si se encontró, si no `false`) — mismo patrón de nombres de claim que ya usa el branch OIDC de `OAuth2AuthenticationSuccessHandler` para Google, de forma que ese handler pueda tratar ambos proveedores con el mismo par de claims sin bifurcar lógica de negocio. El mapa de atributos del `OAuth2User` base es inmutable — copiar a un `new HashMap<>(baseUser.getAttributes())`, añadir las dos claves, y pasar ese mapa al nuevo `DefaultOAuth2User(authorities, mergedAttributes, "id")`. **El tercer argumento (`nameAttributeKey`) debe seguir siendo `"id"`** (el mismo que `CommonOAuth2Provider.GITHUB` ya configura) — cambiarlo rompe `principal.getName()` y por tanto `providerUserId` en la Task 3.
  - [x] **Manejo de fallo de la llamada a `/user/emails` (AC #4):** cualquier excepción de la llamada HTTP (timeout, DNS, 4xx/5xx de GitHub, JSON malformado) se captura y se relanza como `OAuth2AuthenticationException` (nunca una excepción cruda) — Spring Security's `OAuth2LoginAuthenticationFilter` ya captura `OAuth2AuthenticationException` internamente y la enruta al `AuthenticationFailureHandler` configurado (el mismo `OAuth2AuthenticationFailureHandler` de la Story 2.1, sin cambios) — este es el mecanismo estándar de Spring Security para errores de `OAuth2UserService.loadUser()`, no una invención de esta historia. Resiliencia formal (circuit breaker/timeout con Resilience4j sobre esta llamada) es Story 5.2 (AD-17 ya referencia FR-6/Google/GitHub como binds — mismo criterio que Story 2.1 aplicó para la llamada a Google, ver sus Dev Notes); esta historia solo garantiza que un fallo no escala a `500`, no que el circuito se abra automáticamente.
  - [x] Registrar como `@Component` (o `@Bean` en `SecurityConfig`, según convenga a la inyección) e inyectarlo en `SecurityConfig.securityFilterChain` vía `.oauth2Login(oauth2 -> oauth2.userInfoEndpoint(endpoint -> endpoint.userService(gitHubOAuth2UserService)))` — este `userService(...)` **solo** afecta el flujo no-OIDC (GitHub); el flujo OIDC de Google (`oidcUserService`, no tocado) sigue exactamente igual, sin riesgo de regresión sobre la Story 2.1.
  - [x] Tests unitarios con un `RestClient`/llamada HTTP mockeada (MockWebServer o mock del cliente HTTP elegido): (a) email primario verificado presente → atributos `email`/`email_verified=true` añadidos correctamente; (b) ningún email verificado (todos `verified=false` o ninguno `primary`) → `email_verified=false`, `email=null`, sin excepción; (c) la llamada a `/user/emails` falla (timeout/5xx) → `OAuth2AuthenticationException`, nunca una excepción no controlada; (d) `registrationId` distinto de `github` (p. ej. si en el futuro se añade otro proveedor no-OIDC) → delega tal cual sin llamar a la API de GitHub.

- [x] Task 3: `OAuth2AuthenticationSuccessHandler` — leer el claim sintético `email_verified` también en el branch no-OIDC (AC: #1, #2, #3)
  - [x] El branch `else` actual (`principal` no es `OidcUser`) hoy hardcodea `emailVerified = false` — dejarlo así rechazaría **todo** login de GitHub incondicionalmente, rompiendo el AC #1/#2 de esta historia. Cambiar a leer el atributo sintético que la Task 2 ya añade: `Boolean verified = principal.getAttribute("email_verified"); emailVerified = verified != null && verified;` — mismo patrón exacto que el branch `OidcUser` ya usa para el claim nativo de Google, ahora unificado por forma de dato en vez de por tipo de principal.
  - [x] `email = principal.getAttribute("email")` **no cambia** — ya lee el atributo `email`, que la Task 2 ahora sobreescribe con el valor verificado en vez del email público (potencialmente `null`) que devolvía `GET /user` por defecto.
  - [x] `providerUserId = principal.getName()` **no cambia** — ya devuelve el `id` numérico de GitHub como `String` (configurado por `CommonOAuth2Provider.GITHUB` como `user-name-attribute`), consistente con `federated_identities.provider_user_id` (AD-13, mismo campo que usa Google con el claim `sub`).
  - [x] `registrationId = oauthToken.getAuthorizedClientRegistrationId()` **no cambia** — ya será `"github"`, y `FederatedProvider.valueOf("GITHUB")` ya existe desde la Story 2.1 (declarado exactamente para esta historia, nunca usado hasta ahora).
  - [x] Actualizar `OAuth2AuthenticationSuccessHandlerTest`: nuevo caso construyendo un `OAuth2AuthenticationToken`/`DefaultOAuth2User` a mano (no `OidcUser`) con `registrationId="github"`, atributos `email`/`email_verified` — verificar que delega correctamente en `FederatedLoginUseCase` con `emailVerified` reflejando el atributo, no hardcodeado a `false`.

- [x] Task 4: `SecurityConfig` — wiring del nuevo `userService` (AC: #1, #4)
  - [x] `PUBLIC_ENDPOINTS` **no cambia** — `/oauth2/**` y `/login/oauth2/**` ya son agnósticos del `registrationId` (cualquier proveedor configurado en `ClientRegistrationRepository` queda cubierto, confirmado por el propio código de la Story 2.1).
  - [x] Única adición: `.userInfoEndpoint(endpoint -> endpoint.userService(gitHubOAuth2UserService))` dentro del `.oauth2Login(...)` ya existente (Task 2) — no crear una segunda `SecurityFilterChain` (deny-all de AD-11 sigue siendo uno solo).
  - [x] Los slice tests que ya mockean los 3 colaboradores OAuth2 de la Story 2.1 (`SecurityConfigTest`, `AuthControllerTest`, `UserControllerTest`) necesitan ahora un cuarto `@MockitoBean GitHubOAuth2UserService` para que `SecurityConfig` siga cargando. **Nota:** `RefreshTokenRepositoryAdapterTest` (`webEnvironment=NONE`) resultó **no** necesitarlo — a diferencia de los otros 3 colaboradores (que dependen de `FederatedLoginUseCase`/repositorios reales), `GitHubOAuth2UserService` tiene un constructor sin argumentos autosuficiente (`new DefaultOAuth2UserService()` + `RestClient.create()`), así que Spring lo construye real sin problema incluso sin contexto web; verificado con la suite completa, sin necesidad de mock ahí.

- [x] Task 5: Tests de integración (AC: #1, #2, #3, #4)
  - [x] `GET /oauth2/authorization/github` sin autenticar → redirección (3xx) hacia `github.com/login/oauth/authorize`, nunca `401` — mismo patrón que el test equivalente de Google en la Story 2.1.
  - [x] Igual que en la Story 2.1, no es posible ni deseable invocar la API real de GitHub en tests — invocar `FederatedLoginUseCase` directamente con un `FederatedLoginCommand(provider="github", ...)` de prueba (simulando lo que `OAuth2AuthenticationSuccessHandler` + `GitHubOAuth2UserService` ya habrían extraído), contra PostgreSQL real (Testcontainers, mismo patrón que `GoogleLoginIntegrationTest`): Cuenta + Identidad Federada `github` persistidas, Access Token resultante válido contra `GET /api/v1/users/me`.
  - [x] Caso AC #2 en integración: persistir una Cuenta existente (local o ya vinculada a Google) y luego invocar `FederatedLoginUseCase` con `provider="github"` y el mismo email → verificar una sola fila en `accounts`, nueva fila en `federated_identities` para `github` apuntando a esa misma Cuenta, y que la Cuenta ahora tiene ambas Identidades Federadas si aplica. Se cubrieron ambos casos (Cuenta local con password y Cuenta ya vinculada a Google) en `GitHubLoginIntegrationTest`.
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Stories 1.1-1.7 y 2.1 — prestar particular atención a `OAuth2AuthenticationSuccessHandlerTest`, `SecurityConfigTest` y cualquier test que dependa del wiring de `oauth2Login()`. **Nota de entorno:** Docker no estaba disponible en esta sesión (Docker Desktop no corriendo) — los tests de integración con Testcontainers (`GitHubLoginIntegrationTest` incluido) se saltan automáticamente en vez de fallar (comportamiento documentado en el README), verificado que compilan y se saltan limpiamente; no ejecutados en verde contra PostgreSQL real en esta sesión.

### Review Findings

- [x] [Review][Defer] `GitHubLoginIntegrationTest` (Testcontainers, AC #1/#2/#3) no se había ejecutado en la sesión de desarrollo por falta de Docker — resuelto en revisión: Docker estaba disponible, se corrió `./mvnw -B verify -Dtest=GitHubLoginIntegrationTest` y los 4 tests pasaron en verde contra PostgreSQL real (redirección 3xx a GitHub, alta de cuenta nueva + identidad federada, vínculo a cuenta local, vínculo a cuenta ya-Google). [GitHubLoginIntegrationTest.java]
- [x] [Review][Defer] El branch no-OIDC de `OAuth2AuthenticationSuccessHandler` confía en `email_verified` para cualquier proveedor no-OIDC futuro, no solo GitHub — decisión del usuario: se acepta el diseño actual (contrato sintético `email`/`email_verified`, deliberado y ya marcado como `[ASSUMPTION]` en los Dev Notes); un proveedor no-OIDC futuro deberá replicar el mismo patrón de verificación de `GitHubOAuth2UserService` antes de wirearse. No se cambia código. [OAuth2AuthenticationSuccessHandler.java:77-83]

- [x] [Review][Patch] Sin timeout de conexión/lectura en la llamada HTTP a `GET /user/emails` — aplicado: `RestClient.builder().requestFactory(...)` con `SimpleClientHttpRequestFactory` (connect 3s / read 5s). [GitHubOAuth2UserService.java:42-62]
- [x] [Review][Patch] `ClassCastException`/`NullPointerException` sin capturar podían escapar de `fetchPrimaryVerifiedEmail` — aplicado: el parseo/stream ahora vive dentro del mismo `try` que la llamada HTTP, capturando `RestClientException | ClassCastException | NullPointerException`; cubierto por 2 tests nuevos (email no-String, elemento `null` en el array). [GitHubOAuth2UserService.java:80-110]
- [x] [Review][Patch] Sin ningún log cuando falla la llamada a `/user/emails` — aplicado: `log.warn(...)` en el catch antes de relanzar `OAuth2AuthenticationException`. [GitHubOAuth2UserService.java:106]
- [x] [Review][Patch] Falta un test para el caso explícito del AC #2 de login-GitHub-dos-veces — aplicado: nuevo test `repeatedGitHubLoginWithSameProviderUserIdDoesNotDuplicateTheFederatedIdentityRow`, verde contra PostgreSQL real. [GitHubLoginIntegrationTest.java]
- [x] [Review][Patch] Sin header `X-GitHub-Api-Version` en la llamada a `/user/emails` — aplicado: header fijo `"2022-11-28"`. [GitHubOAuth2UserService.java:86]

Suite completa tras aplicar los patches: **212/212 tests en verde** (`./mvnw -B verify`, Docker disponible, incluyendo `GitHubLoginIntegrationTest` contra PostgreSQL real).

## Dev Notes

- **Toda la infraestructura de dominio/aplicación/persistencia de la Story 2.1 se reutiliza sin cambios:** `FederatedProvider.GITHUB` (ya declarado), `FederatedIdentity`, `FederatedIdentityRepository` + su adapter, la migración `V4__federated_identities.sql`, `FederatedLoginUseCase` (genérico por diseño, ahora además con el guard de `Account.status()` cerrado en la revisión de la Story 2.1), `TokenIssuer`, `CookieOAuth2AuthorizationRequestRepository`, `OAuth2AuthenticationFailureHandler`, `OAuth2RedirectProperties`. Esta historia **no** toca ninguno de esos archivos salvo `OAuth2AuthenticationSuccessHandler` (Task 3, un solo `if`) y `SecurityConfig` (Task 4, una línea de wiring).
- **La única fricción técnica real de esta historia es la ausencia de `email_verified` en el flujo no-OIDC de GitHub** (a diferencia de Google, que lo trae gratis en el `id_token` vía OIDC). La solución estándar de la industria — confirmada por búsqueda externa 2026-07-15 e investigación directa contra la documentación de GitHub — es un `OAuth2UserService` personalizado que hace una llamada adicional a `GET /user/emails` con el scope `user:email` y fusiona el resultado en los atributos del `OAuth2User`. No es una invención de esta historia.
- **Contrato de datos elegido — atributos sintéticos `email`/`email_verified`:** en vez de bifurcar `OAuth2AuthenticationSuccessHandler` por tipo de proveedor, `GitHubOAuth2UserService` normaliza sus datos al mismo par de claims que Google ya expone nativamente vía OIDC. Esto mantiene `OAuth2AuthenticationSuccessHandler` estable y solo requiere ampliar una línea (Task 3) en vez de añadir una tercera rama de proveedor. **[ASSUMPTION]** — el nombre exacto de estos atributos sintéticos (`email`, `email_verified`) no está impuesto por ningún AC de epics.md/PRD; se eligió por consistencia con el claim OIDC estándar de Google, documentado aquí para que un revisor humano pueda confirmarlo.
- **Fallo de la llamada a `/user/emails` termina en el mismo contrato de error que un fallo del propio proveedor (AC #4):** igual que la Story 2.1 estableció que "falló en Google" y "falló en nuestra validación" comparten el mismo `failureRedirectUri` sin bifurcar, un fallo de red/HTTP al consultar el email de GitHub se envuelve en `OAuth2AuthenticationException` para que Spring Security lo enrute al mismo `AuthenticationFailureHandler` existente — no se necesita código nuevo de manejo de errores en `SecurityConfig` ni en los handlers ya existentes.
- **Resiliencia (NFR-12/AD-17) sobre la llamada a `/user/emails` formalmente es Story 5.2, no bloquea esta historia** — mismo razonamiento que la Story 2.1 ya documentó para la llamada a Google: AD-17/Story 5.2 refieren FR-6 (Google **y** GitHub) como binds, es decir 5.2 depende de que 2.1 y 2.2 existan primero, no al revés. Esta historia solo garantiza que un fallo no escala a `500` (Task 2), no que el circuito se abra/cierre automáticamente.
- **`OAuth2AuthenticationSuccessHandlerTest` de la Story 2.1 ya cubre el branch `else` con `emailVerified` hardcodeado a `false`** — ese test deja de reflejar el comportamiento real tras esta historia (el branch ahora lee el atributo sintético) y debe actualizarse, no solo añadirse un caso nuevo.
- **Scope de GitHub — atención al valor exacto:** el scope por defecto de `CommonOAuth2Provider.GITHUB` en Spring Boot es `read:user` únicamente; sin `user:email` explícito en la configuración, la llamada de la Task 2 a `/user/emails` devolvería `403` (scope insuficiente) para cualquier usuario, tratado como fallo/no verificado — la Task 1 es un prerequisito estricto de la Task 2, no un detalle cosmético.

### Project Structure Notes

- **Nuevo en `infrastructure/adapters/oauth/`:** `GitHubOAuth2UserService.java`.
- **Modificados:** `OAuth2AuthenticationSuccessHandler.java` (leer `email_verified` también en el branch no-OIDC), `SecurityConfig.java` (`.userInfoEndpoint(...)` dentro de `.oauth2Login(...)`), `application.properties` (registro `github` bajo la sección `OAUTH2` ya existente).
- **Sin cambios:** todo lo demás de `domain/`, `application/usecase/`, `infrastructure/adapters/postgresql/`, `FederatedLoginUseCase`, `TokenIssuer`, `CookieOAuth2AuthorizationRequestRepository`, `OAuth2AuthenticationFailureHandler`, `OAuth2RedirectProperties`, `.env.example`, `GetOwnProfileUseCase`/`UserController` (ya genéricos desde la Story 2.1, Task 9).
- **Nueva migración:** ninguna — `federated_identities` ya soporta cualquier valor de `FederatedProvider` desde `V4__federated_identities.sql`.

### References

- [Source: docs/planning-artifacts/epics.md#Epic-2] — Story 2.2 completa (user story + AC Given/When/Then), relación de reutilización con Story 2.1, FR-6
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-6] — "Autenticación con Google y GitHub", mismo par de tokens para ambos proveedores
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-17] — resiliencia ante Google/GitHub/SMTP, binds explícitos a FR-6, formalmente Story 5.2
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Structural-Seed] — `adapters/oauth/` ya anticipa ambos proveedores en el diagrama
- [Source: docs/implementation-artifacts/2-1-login-con-google.md] — infraestructura completa reutilizada (`FederatedLoginUseCase`, `FederatedProvider.GITHUB` ya declarado con intención explícita de esta historia, patrón de handlers, guard de `Account.status()` cerrado en su revisión de 2026-07-15)
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandler.java] — branch `else` (no-OIDC) ya preparado como punto de extensión para GitHub, hoy con `emailVerified` hardcodeado a `false`
- [Source: src/main/java/com/auth_service/auth/config/SecurityConfig.java] — `.oauth2Login(...)` ya wireado (Story 2.1), único punto que necesita `.userInfoEndpoint(...)`
- [Source: src/main/resources/application.properties, .env.example] — `GITHUB_CLIENT_ID`/`GITHUB_CLIENT_SECRET` ya declaradas desde la Story 2.1, sin usar
- [Source externo: docs.github.com/en/rest/users/emails, fetch directo 2026-07-15] — forma exacta de la respuesta de `GET /user/emails` (`email`, `primary`, `verified`, `visibility`), scope `user:email` requerido
- [Source externo: búsqueda web 2026-07-15, "Spring Security OAuth2 client GitHub login verified email OAuth2UserService"] — patrón estándar de `OAuth2UserService` personalizado que enriquece los atributos con una llamada adicional a `/user/emails`, confirmado por múltiples fuentes independientes (codejava.net, Spring Security reference docs, tutoriales 2026)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -o compile` — `GitHubOAuth2UserService` compiló sin errores en el primer intento (delegate + `RestClient` inyectables por constructor package-private para tests, constructor público sin argumentos para Spring).
- `./mvnw -o test -Dtest=GitHubOAuth2UserServiceTest` — primer intento falló en compilación: `assertThat(result.getAttribute("email"))` era ambiguo entre las sobrecargas `assertThat(IntPredicate)`/`assertThat(Predicate<T>)` de AssertJ al no poder inferirse `T` del `getAttribute` genérico sin tipo objetivo; corregido con el type witness explícito `result.<String>getAttribute(...)`. 4/4 en verde tras el fix — casos: email primario verificado, ningún email verificado, fallo HTTP de `/user/emails` (`OAuth2AuthenticationException`, nunca cruda), `registrationId` no-GitHub delega sin llamar a la API.
- `./mvnw -o test -Dtest=OAuth2AuthenticationSuccessHandlerTest` — se detectó que el test existente de la Story 2.1 **no** cubría en absoluto el branch `else` (no-OIDC) pese a lo que sugerían los Dev Notes de esta historia; se añadieron dos casos nuevos en vez de solo actualizar uno existente. 5/5 en verde.
- `./mvnw -o test -Dtest=SecurityConfigTest,AuthControllerTest,UserControllerTest,RefreshTokenRepositoryAdapterTest` — igual que en la Story 2.1, los 3 slice tests que cargan `SecurityConfig` con `@WebMvcTest`/`@SpringBootTest` necesitaron un `@MockitoBean GitHubOAuth2UserService` nuevo. `RefreshTokenRepositoryAdapterTest` (`webEnvironment=NONE`) resultó **no** necesitarlo — su constructor sin argumentos es autosuficiente. 28/28 en verde (5 de `RefreshTokenRepositoryAdapterTest` saltados por falta de Docker, no fallidos).
- `./mvnw -o test` (suite completa) — **209 tests, 0 failures, 0 errors, 42 skipped** (Testcontainers sin Docker disponible en esta sesión — Docker Desktop no estaba corriendo, comportamiento de skip documentado en el README, no un fallo). `ArchitectureRulesTest` 3/3 en verde.

### Completion Notes List

- Las 5 AC están implementadas: alta de Cuenta `ACTIVE` sin password + Identidad Federada `github` nueva (AC #1, reutilizando `FederatedLoginUseCase`/`TokenIssuer`/tabla sin modificarlos); vinculación a Cuenta local o ya vinculada a Google sin duplicarla (AC #2, cubierto explícitamente con ambos casos en `GitHubLoginIntegrationTest`); resolución del email primario verificado vía `GET /user/emails` con rechazo controlado si no hay ninguno verificado o la llamada falla (AC #3); ningún estado parcial y error controlado ante fallo/cancelación, nunca un 500 (AC #4, `OAuth2AuthenticationException` enrutada por el mecanismo estándar de Spring Security al mismo `AuthenticationFailureHandler`); perfil propio expone ambas Identidades Federadas sin cambios de código adicionales (AC #5, ya genérico desde la Story 2.1).
- Único componente nuevo de producción: `GitHubOAuth2UserService` — normaliza el email verificado de GitHub a los mismos atributos sintéticos `email`/`email_verified` que Google ya expone vía OIDC, evitando que `OAuth2AuthenticationSuccessHandler` bifurque lógica de negocio por proveedor (solo una línea cambiada ahí, más `SecurityConfig` con una línea de wiring).
- Se corrigió una inexactitud en los Dev Notes de la propia historia: afirmaban que `OAuth2AuthenticationSuccessHandlerTest` de la Story 2.1 ya cubría el branch `else` con `emailVerified` hardcodeado a `false` — en realidad ese test no existía; se añadió desde cero (dos casos: verificado y no verificado) en vez de solo actualizar uno.
- Diseño deliberado para testabilidad: `GitHubOAuth2UserService` recibe su `delegate` (`OAuth2UserService<OAuth2UserRequest, OAuth2User>`) y `RestClient` por constructor package-private, evitando depender de mocks de bajo nivel de `DefaultOAuth2UserService`/`RestOperations`; los tests unitarios usan `MockRestServiceServer.bindTo(RestClient.Builder)` (Spring Framework 6.2, ya en el classpath vía `spring-test`) para controlar la respuesta HTTP de `/user/emails` sin red real.
- Docker Desktop no estaba corriendo en esta sesión de desarrollo — todos los tests de integración con Testcontainers (incluido el nuevo `GitHubLoginIntegrationTest`) se saltaron automáticamente en vez de fallar (comportamiento ya documentado en el README para este escenario); compilan correctamente y no han sido ejecutados en verde contra PostgreSQL real en esta sesión. Recomendado ejecutarlos con Docker disponible antes de dar la historia por completamente verificada en CI.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/GitHubOAuth2UserService.java`
- `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/GitHubOAuth2UserServiceTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/GitHubLoginIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandler.java` (branch no-OIDC lee el atributo sintético `email_verified` en vez de hardcodear `false`)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (`.userInfoEndpoint(...)` con `GitHubOAuth2UserService` dentro de `.oauth2Login(...)`)
- `src/main/resources/application.properties` (registro `github` bajo la sección `OAUTH2`)
- `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/OAuth2AuthenticationSuccessHandlerTest.java` (dos casos nuevos para el branch no-OIDC)
- `src/test/java/com/auth_service/auth/config/SecurityConfigTest.java` (`@MockitoBean GitHubOAuth2UserService`, propiedades `github.*`, test de ruta pública `/oauth2/authorization/github`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (`@MockitoBean GitHubOAuth2UserService`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/UserControllerTest.java` (`@MockitoBean GitHubOAuth2UserService`)

## Change Log

| Fecha | Cambio |
|---|---|
| 2026-07-15 | Creación de la Story 2.2 a partir de epics.md (Epic 2), reutilizando la infraestructura de la Story 2.1, e investigación externa sobre `GET /user/emails` de GitHub y el patrón de `OAuth2UserService` personalizado en Spring Security. |
| 2026-07-15 | Implementación de la Story 2.2 (Tasks 1-5): login federado con GitHub completo — `ClientRegistration` + scope `user:email`, `GitHubOAuth2UserService` para resolver el email primario verificado, unificación del branch no-OIDC de `OAuth2AuthenticationSuccessHandler` con el mismo par de claims que Google, wiring en `SecurityConfig`, y tests unitarios/slice/integración. Corregida una inexactitud detectada en los Dev Notes de la propia historia sobre cobertura de test preexistente. |
