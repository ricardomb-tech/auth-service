---
baseline_commit: 5304ece0a7109d73447001b27b6a181b20af5945
context:
  - docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md
  - docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md
  - docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md
---

# Story 5.2: Resiliencia ante proveedores externos

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Operador,
I want que una caída de Google, GitHub o el proveedor de email no cuelgue ni degrade el resto del servicio,
so that un incidente externo no se convierta en un incidente propio. (NFR-12)

## Acceptance Criteria

1. Dado que el proveedor Google OAuth2 (Story 2.1) **o** GitHub OAuth2 (Story 2.2) no responde dentro del timeout configurado, cuando un visitante intenta login social, entonces el `CircuitBreaker`/`TimeLimiter` de Resilience4j devuelve un error controlado (`OAuth2AuthenticationException`, ruteado por el mecanismo estándar de Spring Security al `failureHandler` ya existente) en vez de colgar la petición — y el resto del servicio (login con credenciales, refresh, admin, etc.) sigue operando con normalidad (AD-17). Ambos proveedores están en alcance por igual: el AC del epic solo menciona Google explícitamente, pero AD-17 y el "I want" de esta historia nombran los dos.
2. Dado que fallos/timeouts consecutivos hacia el proveedor de email superan el umbral configurado, cuando el circuito se abre, entonces las operaciones que dependen de email (registro, recuperación de contraseña, aviso de bloqueo) siguen completándose (AD-9, ya implementado — no se toca) pero el intento de envío falla rápido en vez de arriesgar un hilo colgado indefinidamente, y el estado de cada circuito (`github`, `google`, `email`) es una métrica visible en `/actuator/prometheus` (AD-16, AD-17).
3. Dado que el proveedor externo se recupera, cuando pasa el `wait-duration-in-open-state` configurado del circuito, entonces el circuito transiciona a half-open y, si las llamadas de prueba tienen éxito, vuelve a closed automáticamente — sin reinicio del servicio ni intervención manual.

## Tasks / Subtasks

- [ ] Task 1: Configuración base de Resilience4j (AC: #1, #2, #3)
  - [ ] En `src/main/resources/application.properties`, agregar tres instancias nombradas (`github`, `google`, `email`) de `CircuitBreaker` y `TimeLimiter`. Todos los umbrales configurables por env var (NFR-7) — Resilience4j ya soporta el binding relajado estándar de Spring Boot (`RESILIENCE4J_CIRCUITBREAKER_INSTANCES_GITHUB_WAITDURATIONINOPENSTATE=...`), no crear una `@ConfigurationProperties` nueva para esto (mismo criterio que Story 5.1 aplicó a `management.server.port`: es config nativa de un starter, no un umbral de negocio propio).
    ```properties
    # ===============================
    # RESILIENCIA ANTE PROVEEDORES EXTERNOS (AD-17, NFR-12)
    # ===============================
    resilience4j.circuitbreaker.instances.github.sliding-window-size=10
    resilience4j.circuitbreaker.instances.github.minimum-number-of-calls=5
    resilience4j.circuitbreaker.instances.github.failure-rate-threshold=50
    resilience4j.circuitbreaker.instances.github.wait-duration-in-open-state=${RESILIENCE4J_GITHUB_WAIT_DURATION:30s}
    resilience4j.circuitbreaker.instances.github.permitted-number-of-calls-in-half-open-state=3

    resilience4j.circuitbreaker.instances.google.sliding-window-size=10
    resilience4j.circuitbreaker.instances.google.minimum-number-of-calls=5
    resilience4j.circuitbreaker.instances.google.failure-rate-threshold=50
    resilience4j.circuitbreaker.instances.google.wait-duration-in-open-state=${RESILIENCE4J_GOOGLE_WAIT_DURATION:30s}
    resilience4j.circuitbreaker.instances.google.permitted-number-of-calls-in-half-open-state=3

    resilience4j.circuitbreaker.instances.email.sliding-window-size=10
    resilience4j.circuitbreaker.instances.email.minimum-number-of-calls=5
    resilience4j.circuitbreaker.instances.email.failure-rate-threshold=50
    resilience4j.circuitbreaker.instances.email.wait-duration-in-open-state=${RESILIENCE4J_EMAIL_WAIT_DURATION:30s}
    resilience4j.circuitbreaker.instances.email.permitted-number-of-calls-in-half-open-state=3

    resilience4j.timelimiter.instances.github.timeout-duration=${RESILIENCE4J_GITHUB_TIMEOUT:5s}
    resilience4j.timelimiter.instances.google.timeout-duration=${RESILIENCE4J_GOOGLE_TIMEOUT:5s}
    resilience4j.timelimiter.instances.email.timeout-duration=${RESILIENCE4J_EMAIL_TIMEOUT:10s}
    ```
    Valores de ejemplo razonables, no sagrados — lo que el AC exige es que existan y sean configurables, no un número específico.
  - [ ] **`resilience4j-spring-boot3` 2.4.0 ya está en el `pom.xml`** (Story 1.1) — no agregar `resilience4j-reactor` (el proyecto es Spring MVC bloqueante, no WebFlux) ni `resilience4j-micrometer` por separado (el starter de Spring Boot 3 autoconfigura el binding a Micrometer cuando `micrometer-registry-prometheus` ya está presente, que lo está).
  - [ ] Crear `src/main/java/com/auth_service/auth/config/ExternalCallExecutorConfig.java` con un `@Bean Executor externalCallExecutor()` (`ThreadPoolTaskExecutor`, pool pequeño — p. ej. `corePoolSize=4`, `maxPoolSize=8`, `queueCapacity=50`, `threadNamePrefix="external-call-"`). Este executor es el que reciben `CompletableFuture.supplyAsync(..., executor)`/`runAsync(..., executor)` en las Tasks 2-4 — **no usar el `ForkJoinPool.commonPool()` por defecto** (compartido con el resto de la JVM, sin nombre para diagnóstico, tamaño atado a núcleos de CPU). Un único executor compartido por los tres proveedores es suficiente: el aislamiento de fallos lo dan los `CircuitBreaker` nombrados por separado (`github`/`google`/`email`), no pools de hilos separados — `@Bulkhead` no está pedido por ningún AC de esta historia, no agregarlo.

- [ ] Task 2: Resiliencia en el login con GitHub (AC: #1)
  - [ ] **Trampa crítica: `@TimeLimiter` de Resilience4j solo aplica a métodos que devuelven `CompletionStage`/`CompletableFuture` — nunca a un método síncrono.** `GitHubOAuth2UserService.loadUser(OAuth2UserRequest)` implementa la interfaz `OAuth2UserService<OAuth2UserRequest, OAuth2User>` de Spring Security, que exige devolver `OAuth2User` de forma síncrona — no se puede anotar directamente. Patrón a aplicar (verificado contra la documentación de Resilience4j 2.x): extraer el cuerpo actual de `loadUser` (la llamada a `delegate.loadUser(userRequest)` + `fetchPrimaryVerifiedEmail(...)`) a un método privado nuevo que envuelva ambas llamadas en un `CompletableFuture.supplyAsync(() -> ..., externalCallExecutor)`, anotado `@CircuitBreaker(name = "github", fallbackMethod = "loadUserFallback")` y `@TimeLimiter(name = "github")`. El método `loadUser(...)` público llama a `.join()` sobre ese `CompletableFuture` y **debe** desenvolver la `CompletionException` que `.join()` lanza si el interior falló:
    ```java
    try {
        return loadUserResilient(userRequest).join();
    } catch (CompletionException ex) {
        if (ex.getCause() instanceof OAuth2AuthenticationException oauthEx) {
            throw oauthEx;
        }
        throw new OAuth2AuthenticationException(
                new OAuth2Error("github_login_failed", "No se pudo completar el login con GitHub.", null), ex.getCause());
    }
    ```
    Sin este desenvolvimiento, `OAuth2LoginAuthenticationFilter` no reconocería la excepción como `OAuth2AuthenticationException` (rompería el enrutamiento existente al `failureHandler`, AD-8).
  - [ ] El método `fallbackMethod` (`loadUserFallback`) debe declarar **el mismo tipo de retorno** que el método guardado (`CompletableFuture<OAuth2User>`), con los mismos parámetros más un `Throwable` final — convención obligatoria de Resilience4j para fallbacks de métodos asíncronos (si el tipo no coincide, falla en tiempo de arranque al crear el proxy AOP, no en tiempo de ejecución). El fallback simplemente re-lanza como `OAuth2AuthenticationException` (mismo patrón que el catch existente de `fetchPrimaryVerifiedEmail`) o devuelve `CompletableFuture.failedFuture(...)` con esa excepción — cualquiera de las dos formas es válida, elegir la que quede más simple.
  - [ ] Envolver **ambas** llamadas externas (`delegate.loadUser` + `fetchPrimaryVerifiedEmail`) en un solo `CircuitBreaker`/`TimeLimiter` "github" — no separarlas en dos circuitos. Es lo mismo request GitHub visto como una unidad de trabajo; separar circuitos no aporta nada que ningún AC pida.
  - [ ] **Fuera de alcance deliberado:** el intercambio código→token (`DefaultAuthorizationCodeTokenResponseClient`, mecanismo interno de Spring Security antes de que `loadUser` se invoque) no se envuelve en esta historia — el código existente ya solo customiza el paso de user-info/email (ver Story 2.2), y envolver también el token endpoint requeriría reemplazar un bean de Spring Security que ningún AC pide tocar. Si se necesita en el futuro, es candidato a `deferred-work.md`.

- [ ] Task 3: Resiliencia en el login con Google (AC: #1)
  - [ ] **Hoy no existe ninguna clase custom para Google** — `SecurityConfig.java:152-154` usa el `oidcUserService` por defecto de Spring Security sin envolver nada (comentario: "flujo OIDC de Google (oidcUserService, no tocado) sigue exactamente igual"). Esta historia SÍ lo toca: crear `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/GoogleOidcUserService.java`, simétrico a `GitHubOAuth2UserService` pero implementando `OAuth2UserService<OidcUserRequest, OidcUser>` (la interfaz OIDC, no la genérica) y delegando en una instancia de `OidcUserService` (la clase por defecto de Spring Security, no una interfaz — instanciar directamente `new OidcUserService()` como delegado, mismo patrón que `GitHubOAuth2UserService` usa `new DefaultOAuth2UserService()`).
  - [ ] Mismo patrón de Task 2: método privado asíncrono envolviendo `delegate.loadUser(userRequest)` con `@CircuitBreaker(name = "google", fallbackMethod = "...")` + `@TimeLimiter(name = "google")`, `.join()` + desenvolvimiento de `CompletionException` en el método público `loadUser(OidcUserRequest)`, fallback re-lanzando `OAuth2AuthenticationException`.
  - [ ] Google **no necesita** la lógica adicional de "fetch primary verified email" que GitHub sí necesita (Google es OIDC — el email verificado ya viene en los claims del ID token, sin llamada adicional) — el único propósito de esta clase es envolver la única llamada externa (`delegate.loadUser`) con resiliencia, no replicar la lógica de atributos sintéticos de GitHub. No copiar esa parte del patrón de GitHub.
  - [ ] Wiring en `SecurityConfig.java`: inyectar el nuevo `GoogleOidcUserService` por constructor (mismo patrón que `gitHubOAuth2UserService`) y agregar `.oidcUserService(googleOidcUserService)` a la misma cadena `.userInfoEndpoint(endpoint -> endpoint.userService(gitHubOAuth2UserService).oidcUserService(googleOidcUserService))` (línea ~154). Verificar que el ArchUnit/`SecurityConfigTest` existente siga en verde tras el cambio.

- [ ] Task 4: Resiliencia en el envío de email (AC: #2)
  - [ ] `src/main/java/com/auth_service/auth/application/event/EmailNotificationListener.java` ya implementa AD-9 correctamente (tres métodos `@TransactionalEventListener(phase = AFTER_COMMIT)`, cada uno con su propio `try { emailSender.sendXxx(...) } catch (RuntimeException ex) { log.error(...); }`) — **no tocar esa estructura**, solo envolver la llamada a `emailSender.sendXxx(...)` dentro de cada `try` con el mismo patrón asíncrono de las Tasks 2-3: un método privado `CompletableFuture<Void> sendXxxResilient(...)` con `@CircuitBreaker(name = "email")` + `@TimeLimiter(name = "email")`, ejecutando `CompletableFuture.runAsync(() -> emailSender.sendXxx(...), externalCallExecutor)`, llamado con `.join()` dentro del `try` existente.
  - [ ] **No se necesita un `fallbackMethod` nuevo para email** — a diferencia de OAuth2, aquí no hace falta traducir a un tipo de excepción específico: el `catch (RuntimeException ex)` que ya existe en cada método del listener captura perfectamente la `CompletionException` que `.join()` lanza (`CompletionException extends RuntimeException`), preservando el comportamiento AD-9 ("un fallo aquí no puede revertir nada") sin ningún cambio en la lógica de captura existente.
  - [ ] **No crear ningún adapter SMTP de producción en esta historia.** Solo existe `LoggingEmailSender` (perfil `!prod`) — la pregunta abierta 1 del PRD (proveedor SMTP real) sigue sin resolver, y no es lo que NFR-12/AD-17 piden. La resiliencia se aplica en el punto de invocación (`EmailNotificationListener`), protegiendo cualquier implementación de `EmailSender` que esté activa hoy o en el futuro (incluida una eventual `SmtpEmailSender`) sin que ese futuro adapter tenga que reimplementar nada.

- [ ] Task 5: Verificar el estado del circuito como métrica (AC: #2)
  - [ ] Levantar el servicio y confirmar empíricamente en `/actuator/prometheus` (puerto 8081, Story 5.1) que aparecen métricas `resilience4j_circuitbreaker_state`/`resilience4j_circuitbreaker_calls_seconds_count` con tag `name="github"|"google"|"email"` — **no asumir que aparecen solo porque la dependencia está en el classpath**. Story 5.1 (Debug Log References, Desvío #4) ya encontró un caso real en este mismo proyecto donde una autoconfiguración de métricas no se activaba pese a tener todo lo necesario (`management.defaults.metrics.export.enabled` resolvía a `false` sin razón determinada); repetir esa misma verificación empírica aquí antes de dar la Task por cerrada.

- [ ] Task 6: Tests (AC: #1, #2, #3)
  - [ ] **No usar un mock de servidor HTTP (WireMock, MockWebServer, etc.)** — ninguna de esas dependencias está en el `pom.xml` hoy y agregar una solo para esta historia sería una dependencia nueva no pedida por ningún AC. En vez de eso, testear el `CircuitBreaker`/`TimeLimiter` unitariamente: construir `GitHubOAuth2UserService`/`GoogleOidcUserService` con un delegado (`OAuth2UserService`/`OidcUserService`) mockeado con Mockito que duerma más del timeout configurado (`Thread.sleep` dentro del stub, vía `when(...).thenAnswer(inv -> { Thread.sleep(...); return ...; })`) o que lance una excepción, y verificar que `loadUser(...)` lanza `OAuth2AuthenticationException` en vez de colgar o propagar la excepción cruda. Instanciar un `CircuitBreakerRegistry`/`TimeLimiterRegistry` de test con configuración de ventana corta (no depender de `application.properties` en un test unitario puro).
  - [ ] Test de recuperación del circuito (AC #3): forzar varias llamadas fallidas seguidas (supera `minimum-number-of-calls`/`failure-rate-threshold`) para abrir el circuito, confirmar que la siguiente llamada falla inmediato con `CallNotPermittedException` (sin siquiera invocar el delegado — verificable con `verify(delegate, times(N))` sin incremento adicional), esperar un `wait-duration-in-open-state` corto configurado solo para el test (p. ej. `1s`, vía `CircuitBreakerConfig.custom()...build()` en el registry de test, no la property de producción), y confirmar que una llamada exitosa tras la espera cierra el circuito de nuevo.
  - [ ] Test de email: mock de `EmailSender` que duerme más del timeout de `email`, invocar el listener directamente (no vía Spring context completo), y confirmar que el método retorna normalmente (no propaga) y logea el error — igual que ya se hace hoy para una `RuntimeException` cruda, solo que ahora la causa es un timeout.
  - [ ] Suite completa sin regresión: `./mvnw test` (requiere Docker + `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` exportadas — sin esas dos env vars, **todos** los `@SpringBootTest` fallan al bindear `OAuth2RedirectProperties`, no solo los de este story; confirmado empíricamente en la Story 5.1).
  - [ ] Confirmar que ningún test existente de `GoogleLoginIntegrationTest`/`GitHubLoginIntegrationTest` (Stories 2.1/2.2) se rompe por el nuevo wrapping — deben seguir pasando exactamente igual en el camino feliz.

## Dev Notes

- **Aspect order de Resilience4j (fijo, no configurable):** Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → Método. Esta historia solo usa CircuitBreaker + TimeLimiter (sin Retry/RateLimiter/Bulkhead — ningún AC los pide), así que el orden entre esas dos anotaciones en el código fuente no importa para el comportamiento, pero sí importa el punto anterior sobre el tipo de retorno.
- **Por qué `@TimeLimiter` obliga a un wrapper asíncrono:** es la trampa central de esta historia. Los tres puntos de integración (`loadUser` de OAuth2/OIDC, `EmailSender.sendXxx`) son síncronos por contrato de interfaz — no se puede cambiar esas firmas (rompería Spring Security / el port de dominio). El patrón "envolver en `CompletableFuture`, aplicar las anotaciones al método async interno, `.join()` en el método síncrono público" es el mecanismo estándar para retrofit de TimeLimiter sobre código bloqueante, no una invención de esta historia.
- **Esta historia es infraestructura/aplicación pura — no toca `domain/`.** `EmailNotificationListener` vive en `application/event` y ya usa anotaciones de Spring (`@TransactionalEventListener`) desde que se implementó AD-9 — añadir `@CircuitBreaker`/`@TimeLimiter` ahí es consistente con ese precedente, no una violación nueva de Clean Architecture. Los dos `*OAuth2UserService`/`*OidcUserService` van en `infrastructure/adapters/oauth/`, igual que `GitHubOAuth2UserService` ya existente.
- **No se crea ninguna `@ConfigurationProperties` nueva para los umbrales de Resilience4j** — son propiedades nativas del starter `resilience4j-spring-boot3`, ya soportan override por variable de entorno vía el binding relajado estándar de Spring Boot (mismo criterio aplicado en Story 5.1 a `management.server.port`).
- **No se construye ningún adapter SMTP de producción** — sigue bloqueado por la pregunta abierta 1 del PRD; esta historia protege el port `EmailSender` sea cual sea la implementación activa.
- **No se envuelve el intercambio código→token de OAuth2** (antes de que `loadUser` se invoque) — fuera de alcance deliberado, ver Task 2.

### Project Structure Notes

- **Nuevos:**
  - `src/main/java/com/auth_service/auth/config/ExternalCallExecutorConfig.java`
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/GoogleOidcUserService.java`
  - `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/GoogleOidcUserServiceTest.java`
  - `src/test/java/com/auth_service/auth/application/event/EmailNotificationListenerTest.java` (no existe todavía ningún test para esta clase — confirmado)
- **Modificados:**
  - `src/main/resources/application.properties` (bloque nuevo `resilience4j.*`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/GitHubOAuth2UserService.java` (refactor interno a wrapper asíncrono + resiliente, misma firma pública `loadUser`)
  - `src/test/java/com/auth_service/auth/infrastructure/adapters/oauth/GitHubOAuth2UserServiceTest.java` (ya existe — agregar los casos de timeout/circuito abierto/recuperación de la Task 6, no crear un archivo paralelo)
  - `src/main/java/com/auth_service/auth/application/event/EmailNotificationListener.java` (cada llamada a `emailSender.sendXxx` envuelta, mismo `try/catch` externo)
  - `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (+`.oidcUserService(googleOidcUserService)` en la cadena `userInfoEndpoint`)
- **Sin cambios:** `domain/`, `application/usecase/`, cualquier controller existente, `docker-compose.yml`, `pom.xml` (todas las dependencias necesarias ya presentes desde Story 1.1), `GitHubLoginIntegrationTest`/`GoogleLoginIntegrationTest` (no ejercitan la llamada de red real de `loadUser`, ya la evitan por diseño — confirmado, no deberían verse afectados).

### References

- [Source: docs/planning-artifacts/epics.md#Story-5.2] — AC Given/When/Then completas, NFR-12
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-17] — regla completa: "toda llamada saliente a Google, GitHub o SMTP pasa por un CircuitBreaker + TimeLimiter de Resilience4j con fallback explícito ... El estado de cada circuito es una métrica expuesta (AD-16)"
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-9] — email fuera de transacción, ya implementado, no tocar la estructura de `EmailNotificationListener`
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Stack] — `resilience4j-spring-boot3` 2.4.0 (verificado, ya en `pom.xml`)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md] — NFR-12, SM-7 (diagnóstico de caída simulada en <5 min combinando esta historia con la observabilidad de 5.1)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md] — restatement del mecanismo concreto: `@CircuitBreaker` + `@TimeLimiter` con fallback en los adapters de Google/GitHub/SMTP
- [Source: src/main/java/com/auth_service/auth/infrastructure/adapters/oauth/GitHubOAuth2UserService.java] — patrón existente a extender (timeouts manuales de `RestClient`, catch→`OAuth2AuthenticationException`)
- [Source: src/main/java/com/auth_service/auth/config/SecurityConfig.java:149-156] — wiring actual de `userInfoEndpoint`, punto exacto donde agregar `.oidcUserService(...)`
- [Source: src/main/java/com/auth_service/auth/application/event/EmailNotificationListener.java] — los tres métodos `@TransactionalEventListener(AFTER_COMMIT)` a envolver
- [Source: src/main/java/com/auth_service/auth/domain/port/EmailSender.java] — firma del port, sin cambios
- [Source: docs/implementation-artifacts/5-1-salud-metricas-y-trazas-distribuidas.md#Debug-Log-References] — Desvío #4 (verificación empírica de métricas Prometheus), patrón de test reutilizable (`@SpringBootTest(RANDOM_PORT)` + Testcontainers), advertencia sobre `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` obligatorias para que arranque CUALQUIER `@SpringBootTest`
- Resilience4j — combinación `@CircuitBreaker`+`@TimeLimiter` sobre `CompletableFuture`, orden fijo de aspectos, convención de `fallbackMethod` con mismo tipo de retorno: https://resilience4j.readme.io/docs/getting-started-3 y https://reflectoring.io/time-limiting-with-springboot-resilience4j/ (verificado 2026-07-23)

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
