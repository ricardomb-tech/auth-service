---
baseline_commit: 07d78351e4e607484477a2b4fc6f0557cb4a278f
---

# Story 5.1: Salud, Métricas y Trazas Distribuidas

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Operador,
I want ver el estado de salud del servicio y sus métricas, y seguir una traza por petición,
so that pueda diagnosticar un incidente sin adivinar. (NFR-11)

## Acceptance Criteria

1. Dado el servicio corriendo, cuando consulto `/actuator/health/readiness` y `/actuator/health/liveness` en el **puerto de management** (distinto del puerto de negocio), entonces reflejan con precisión si el servicio puede recibir tráfico y si está vivo — `readiness` incluye el estado de la conexión a PostgreSQL (`components.db`), `liveness` no depende de ningún recurso externo. Ninguno de los endpoints de `/actuator/**` aparece en la superficie pública de negocio: una petición a `/actuator/**` contra el puerto de negocio no debe alcanzar ningún endpoint de Actuator (AD-11, AD-16).
2. Dado el servicio corriendo con el exportador OTLP configurado, cuando hago una petición HTTP cualquiera contra la superficie de negocio, entonces se genera/propaga un `traceId` (Micrometer Tracing + puente OpenTelemetry) presente en cada línea de log asociada a esa petición, y el log de la aplicación se emite en formato JSON estructurado. `/actuator/prometheus` (puerto de management) expone métricas de latencia y conteo por endpoint (`http.server.requests`) (AD-16).

## Tasks / Subtasks

- [x] Task 1: Puerto de management separado + grupos de salud (AC: #1)
  - [x] En `src/main/resources/application.properties`, reemplazar el bloque actual (líneas 14-18, comentario "Epic 5 configura...") por:
    ```properties
    # ===============================
    # ACTUATOR (AD-16) — puerto de management separado del tráfico de negocio;
    # nunca listado como público en SecurityConfig (AD-11). Al vivir en un
    # puerto HTTP distinto, Spring Boot no registra estas rutas en el
    # DispatcherServlet del puerto de negocio: no hace falta (ni se debe)
    # añadir "/actuator/**" a PUBLIC_ENDPOINTS de SecurityConfig.
    # ===============================
    management.server.port=8081
    management.endpoints.web.exposure.include=health,prometheus,info
    management.endpoint.health.probes.enabled=true
    management.endpoint.health.group.readiness.include=readinessState,db
    management.endpoint.health.show-details=always
    ```
    `management.server.port` es una property nativa de Spring Boot — ya soporta override por variable de entorno estándar `MANAGEMENT_SERVER_PORT` sin necesitar placeholder `${...}` explícito (mismo mecanismo con el que `SERVER_PORT` ya podría overridear `server.port` hoy, aunque el proyecto no lo usa explícitamente todavía). No introducir una `@ConfigurationProperties` nueva para esto — es config nativa de Actuator, no un umbral/TTL de negocio (NFR-7 aplica a config de dominio, no a bindings de infraestructura de un framework).
    `management.endpoint.health.show-details=always` es una decisión consciente: el puerto de management no pasa por el `SecurityFilterChain` de negocio (no hay `Principal` autenticado ahí), así que `when-authorized` ocultaría los detalles siempre — inútil. El puerto de management ya está segregado por AD-16/AD-11 (solo alcanzable desde infraestructura de confianza, no expuesto públicamente); mostrar detalle ahí es el propósito del endpoint.
  - [x] `management.endpoint.health.probes.enabled=true` + el include explícito de `db` en el grupo `readiness` son los dos que producen el comportamiento pedido por el AC #1: `liveness` = solo `livenessState` (nunca depende de recursos externos — es la práctica estándar, un Pod no debe reiniciarse porque Postgres esté lento); `readiness` = `readinessState` + `db` (si Postgres no responde, el Pod sale de rotación de tráfico sin reiniciarse).

- [x] Task 2: Logging JSON estructurado con `traceId` correlacionado (AC: #2)
  - [x] En `src/main/resources/application.properties`, añadir:
    ```properties
    # ===============================
    # LOGGING ESTRUCTURADO + TRAZAS (AD-16) — Boot 3.4 trae structured logging
    # nativo (sin logstash-logback-encoder ni logback-spring.xml a mano); el
    # formato "logstash" incluye el contenido del MDC (traceId/spanId) en el
    # JSON de cada línea automáticamente.
    # ===============================
    logging.structured.format.console=logstash

    # Sampling al 100% en dev/test: el AC pide traceId en "cada línea de log",
    # no solo en las peticiones muestreadas. Ajustable en prod vía env var si
    # el volumen de tráfico lo justifica.
    management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}

    # Sin colector OTLP en docker-compose todavía [ASSUMPTION] — el exportador
    # queda configurado (endpoint estándar, overrideable) pero una caída de
    # conexión al collector no debe romper ninguna petición de negocio; solo
    # loggea el intento de export fallido. Verificar este punto explícitamente
    # con el test de la Task 4.
    management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
    ```
  - [x] **No añadir `logstash-logback-encoder` al `pom.xml`.** Es la trampa obvia de esta historia: casi toda la documentación/tutoriales de "JSON logging en Spring Boot" pre-fecha Boot 3.4 y asume ese encoder + un `logback-spring.xml` manual. Boot 3.4.4 (ya en el `pom.xml` del proyecto) tiene structured logging nativo — la única property de arriba basta. No crear `src/main/resources/logback-spring.xml`.
  - [x] Micrometer Tracing (`micrometer-tracing-bridge-otel`) + `opentelemetry-exporter-otlp` ya están en el `pom.xml` desde la Story 1.1 — no añadir dependencias nuevas para esta historia.
  - [x] **Verificar empíricamente el nombre/ubicación exacta del campo del `traceId` en el JSON antes de escribir el assert del test de la Task 4** — la documentación de Boot 3.4 describe el contenido del MDC incluido en el JSON pero la forma exacta (`traceId` en la raíz vs. anidado bajo `mdc.traceId`) puede variar por versión de patch. Levantar el servicio, hacer una petición, inspeccionar una línea real de log JSON en consola, y solo entonces fijar el path del assert — no asumir la forma sin comprobarla.

- [x] Task 3: Tests de Actuator — grupos de salud y aislamiento de puerto (AC: #1)
  - [x] Nuevo `src/test/java/com/auth_service/auth/ActuatorHealthIntegrationTest.java`, mismo patrón Testcontainers que `AuthServiceApplicationTests` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Testcontainers(disabledWithoutDocker = true)`, `@ServiceConnection` sobre `PostgreSQLContainer<>("postgres:15")`, `@TestPropertySource` con `auth.jwt.secret-current` de test). Para que el puerto de management también sea aleatorio en test (evita colisión con el `8081` fijo si algo más lo usa en CI), añadir `management.server.port=0` al `@TestPropertySource` e inyectar ambos puertos:
    ```java
    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;
    ```
    `@LocalManagementPort` vive en `org.springframework.boot.test.web.server` en Boot 3.4.x (reorganización de paquetes de test respecto a versiones previas — si el IDE no lo resuelve ahí, buscar el paquete correcto vía autocompletado antes de asumir que hay que añadir una dependencia nueva; ya está disponible transitivamente vía `spring-boot-starter-actuator` + `spring-boot-starter-test`).
    - `readinessProbeReturns200WithDbComponentUp` — `GET` a `http://localhost:{managementPort}/actuator/health/readiness`, `status().isOk()`, cuerpo contiene `"status":"UP"`.
    - `livenessProbeReturns200` — `GET` a `http://localhost:{managementPort}/actuator/health/liveness`, `status().isOk()`.
    - `prometheusEndpointIsServedOnManagementPort` — `GET` a `http://localhost:{managementPort}/actuator/prometheus`, `status().isOk()`, cuerpo contiene `http_server_requests` (nombre de la métrica autogenerada por Spring MVC + Micrometer).
    - `actuatorIsNotReachableOnTheBusinessPort` — `GET` a `http://localhost:{serverPort}/actuator/health` (puerto de negocio, no el de management): esperar 404 (Spring Boot no registra esas rutas en ese puerto cuando `management.server.port` difiere de `server.port` — no hay ningún hándler para esa ruta ahí, es un 404 de "no existe", no un 401/403 de seguridad).
  - [x] Usar `RestTemplate`/`TestRestTemplate` en vez de `MockMvc` para estas peticiones — `MockMvc` con `@AutoConfigureMockMvc` apunta solo al `DispatcherServlet` del contexto web principal (puerto de negocio) y no tiene forma de dirigirse al puerto de management, que en `RANDOM_PORT` corre en un contenedor de servlet HTTP real separado.

- [x] Task 4: Test de logging JSON + `traceId` (AC: #2)
  - [x] Nuevo `src/test/java/com/auth_service/auth/StructuredLoggingIntegrationTest.java`, mismo setup Testcontainers/`RANDOM_PORT` que la Task 3. Adjuntar un `ListAppender<ILoggingEvent>` (Logback, `ch.qos.logback.classic`, ya en el classpath transitivo de `spring-boot-starter-logging`) programáticamente al `Logger` raíz (`(Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)`) antes de la petición, y removerlo después (`@AfterEach`) para no interferir con otros tests.
    - `httpRequestLogLineIncludesNonBlankTraceId` — hacer una petición HTTP simple contra un endpoint público existente (p. ej. `GET /v3/api-docs`, ya cubierto como público por `AuthServiceApplicationTests`), capturar los eventos de log emitidos durante esa petición, y verificar que al menos uno de ellos tiene un `traceId` no vacío en su MDC (`event.getMDCPropertyMap().get("traceId")`) — esto valida la propagación del trace context independientemente del formato de salida exacto en consola, y es más robusto que parsear el JSON de stdout.
    - Si el formato JSON en sí también necesita verificarse explícitamente (el AC lo pide: "el log de la aplicación se emite en formato JSON estructurado"), añadir una segunda aserción liviana: capturar una línea de stdout durante la petición (redirigir `System.out` a un `ByteArrayOutputStream` temporalmente) y verificar que es JSON parseable (`new ObjectMapper().readTree(line)` no lanza excepción) — no fijar un schema exacto de campos más allá de eso.
  - [x] Confirmar en el mismo test (o uno adicional) que una caída del exportador OTLP (colector inalcanzable en `http://localhost:4318`, ya el caso por defecto en este entorno de test sin colector levantado) **no** hace fallar la petición HTTP ni el test — el intento de export es asíncrono/best-effort. Si `httpRequestLogLineIncludesNonBlankTraceId` pasa con el colector caído, esto ya queda demostrado implícitamente; no hace falta un test separado solo para esto.

- [x] Task 5: Suite completa sin regresión (AC: #1, #2)
  - [x] `./mvnw test` (requiere Docker para Testcontainers) — 0 fallas, 0 errores, sin tocar ningún archivo de Epics 1-4.
  - [x] Confirmar que `docker-compose up` sigue arrancando limpio (no se tocó `docker-compose.yml` en esta historia — el puerto de management es solo de la app Java, no del stack de Docker Compose).

### Review Findings

- [x] [Review][Decision] El diseño delega toda la seguridad del puerto de management en el aislamiento de red, sin ninguna salvaguarda a nivel de código — `actuatorSecurityFilterChain` (`SecurityConfig.java`) da `permitAll()` incondicional a todo `/actuator/**`, y nada en el diff falla rápido si `management.server.port` llegara a coincidir con `server.port` (p. ej. por una variable de entorno mal puesta en producción). Reportado de forma independiente por las 3 capas de revisión. **Resuelto:** se agregó `ManagementPortSafetyCheck` (`src/main/java/com/auth_service/auth/config/ManagementPortSafetyCheck.java`) — falla el arranque con `IllegalStateException` si ambos puertos coinciden (excepto el caso `0`/aleatorio usado en tests), con test unitario dedicado (`ManagementPortSafetyCheckTest`). **No se agregó** `management.server.address=127.0.0.1`: restringir el bind a loopback rompería probes de un orquestador externo que llegue por la IP del pod/contenedor y no por `localhost` (p. ej. Kubernetes kubelet sin `hostNetwork`) — el repo no tiene todavía Dockerfile/manifiesto de despliegue para saber si aplica; queda como decisión a tomar cuando exista esa topología real (ver deferred-work.md).
- [x] [Review][Decision] `management.tracing.sampling.probability=${TRACING_SAMPLING_PROBABILITY:1.0}` vive en `application.properties` base (no en un perfil `dev`/`test`), así que el 100% de sampling es el default real en producción salvo que un operador recuerde sobreescribir la env var — riesgo latente de latencia/costo frente a NFR-3 (p95 < 500ms) si se olvida. [src/main/resources/application.properties] — **Resuelto: se deja como está.** Ya es overrideable vía `TRACING_SAMPLING_PROBABILITY` y es una decisión consciente y documentada para cumplir la letra del AC #2 ("cada línea de log") en el entorno por defecto.

- [x] [Review][Patch] `httpRequestLogLineIncludesNonBlankTraceId` prueba "al menos una línea tiene traceId", no "cada línea" como pide literalmente el AC #2 — un `traceId` residual de un hilo/petición no relacionada (MDC obsoleto) haría pasar el test igual aunque la propagación real estuviera rota [src/test/java/com/auth_service/auth/StructuredLoggingIntegrationTest.java:70-89] — aplicado: se filtra por el logger de `JwtAuthenticationFilter` (el único que emite en respuesta directa a esta petición) y se exige `allMatch` de traceId no vacío sobre todos sus eventos, no `anyMatch` sobre el root logger completo
- [x] [Review][Patch] `readinessProbeReturns200WithDbComponentUp` nunca verifica que el componente `db`/`components` esté realmente en el cuerpo de la respuesta, solo el `"status":"UP"` global [src/test/java/com/auth_service/auth/ActuatorHealthIntegrationTest.java:46-53] — aplicado: se agregó `assertThat(body).contains("\"db\"")`
- [x] [Review][Patch] `actuatorIsNotReachableOnTheBusinessPort` solo verificaba una ruta (`/actuator/health`) contra el puerto de negocio [src/test/java/com/auth_service/auth/ActuatorHealthIntegrationTest.java:72-82] — aplicado: convertido a `@ParameterizedTest` sobre `/actuator/health`, `/actuator/prometheus`, `/actuator/info` y `/actuator`
- [x] [Review][Patch] Ningún test ejercitaba la rama de PostgreSQL caído (readiness debe reflejar `DOWN`, liveness debe permanecer `UP`) — aplicado: nueva clase `ActuatorHealthDbDownIntegrationTest` (detiene el contenedor de Postgres con `@DirtiesContext(AFTER_CLASS)` para no contaminar la caché de contexto de otras clases); verificado con Docker real: readiness responde 503, liveness sigue en `UP` (49.18s, dominado por el timeout de reconexión de HikariCP)

- [x] [Review][Defer] El exportador OTLP apunta por defecto a `http://localhost:4318` sin ningún colector presente en `docker-compose.yml`; cada petición intentará (y fallará) exportar una traza en cualquier entorno sin colector real — deferred, pre-existing (ya reconocido explícitamente como `[ASSUMPTION]`/trabajo diferido en los propios Dev Notes de esta historia; el volumen de log-noise bajo carga sostenida queda sin verificar)
- [x] [Review][Defer] `consoleOutputIsStructuredJson` no observa el stdout real — reconstruye la línea reutilizando el `Encoder` del appender `CONSOLE` (cast sin chequear, nombre de appender hardcodeado) porque `ConsoleAppender` cachea su `OutputStream` al arrancar — deferred, pre-existing (compromiso de test ya reconocido explícitamente por el propio dev en el Desvío #6 de esta historia; frágil si cambia el wiring de appenders por defecto de Boot) [src/test/java/com/auth_service/auth/StructuredLoggingIntegrationTest.java:500-524]

## Dev Notes

- **Esta historia es 100% configuración + tests — no toca `domain/`, `application/usecase/`, ni ningún controller existente.** No hay AC que pida nueva lógica de negocio. Si en algún punto de la implementación parece necesario crear una clase nueva en `domain/` o `application/`, es señal de estar sobre-construyendo — parar y releer el AC.
- **Todas las dependencias necesarias ya están en el `pom.xml` desde la Story 1.1** (`spring-boot-starter-actuator`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `micrometer-registry-prometheus`) — la Additional Requirements del `epics.md` ya las listó explícitamente por adelantado (ver Referencias). **No añadir `logstash-logback-encoder`**: es la dependencia que casi todo tutorial de JSON logging pre-Boot-3.4 pide, pero aquí es redundante y contradice AD-16 tal como está resuelto en este proyecto (structured logging nativo de Boot 3.4.4).
- **`management.endpoints.web.exposure.include=none` (valor actual) es exactamente el placeholder que el comentario de `application.properties` (línea 14-18) ya anticipaba** — "Epic 5 configura grupos liveness/readiness y el puerto de management separado". Esta historia cierra ese placeholder.
- **Por qué el puerto de management resuelve el AC "ningún endpoint de `/actuator/**` en la superficie pública" sin tocar `SecurityConfig`:** cuando `management.server.port` difiere de `server.port`, Spring Boot arranca un segundo contenedor de servlet HTTP embebido (Tomcat) solo para Actuator, con su propio `DispatcherServlet` — las rutas de Actuator ni siquiera existen en el `ApplicationContext`/mapping del puerto de negocio. No es una regla de autorización que se pueda saltar con una URL, es una superficie HTTP completamente distinta. Por eso `PUBLIC_ENDPOINTS` en `SecurityConfig.java` no necesita (ni debe) mencionar `/actuator/**`.
- **Sampling al 100% (`management.tracing.sampling.probability=1.0`) es una decisión explícita para esta historia**, no el default de Micrometer (10%). El AC dice literalmente "traceId... presente en **cada** línea de log asociada a esa petición" — con sampling parcial, una parte de las peticiones no generaría contexto de traza completo. Queda overrideable por env var (`TRACING_SAMPLING_PROBABILITY`) para que un futuro entorno de alto tráfico pueda bajarlo sin tocar código.
- **Sin colector OTLP en `docker-compose.yml`** — deferred, `[ASSUMPTION]`. El AC no pide "un colector recibiendo las trazas", pide que el `traceId` se genere/propague y aparezca en los logs; el exportador queda configurado y apuntando a un endpoint estándar (overrideable), pero la ausencia de un colector real en local no bloquea ningún AC. Si se necesita observabilidad end-to-end real (Jaeger/Tempo + Grafana) en el futuro, es candidato a `deferred-work.md`, no algo que esta historia deba resolver por su cuenta añadiendo servicios nuevos a `docker-compose.yml` sin que ningún AC lo pida.
- **Patrón de test a reutilizar:** `AuthServiceApplicationTests` (Story 1.1) ya establece el setup base de `@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` + `PostgreSQLContainer<>("postgres:15")` + `@TestPropertySource(properties = "auth.jwt.secret-current=...")` que todas las historias de Epic 5 reutilizan sin cambios. Las historias de esta Epic también necesitan que las variables de entorno `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` estén presentes en el entorno donde corre `mvn test` (obligatorias desde `application.properties`, sin default) — si el test runner no las tiene exportadas, el contexto de Spring no arranca para NINGÚN test `@SpringBootTest`, no solo los de esta historia.

### Project Structure Notes

- **Nuevos:**
  - `src/test/java/com/auth_service/auth/ActuatorHealthIntegrationTest.java`
  - `src/test/java/com/auth_service/auth/StructuredLoggingIntegrationTest.java`
- **Modificados:**
  - `src/main/resources/application.properties` (bloque Actuator existente reemplazado + bloque de logging/tracing nuevo)
- **Sin cambios:** `pom.xml` (todas las dependencias ya presentes), `docker-compose.yml`, cualquier clase de `domain/`, `application/usecase/` o `infrastructure/controller/` existente.
- **Desviación respecto al plan original:** `SecurityConfig.java` **sí** requirió un cambio — ver Debug Log References. El aislamiento por puerto no es suficiente por sí solo para dejar pasar tráfico al puerto de management cuando `spring-boot-starter-security` está en el classpath.

### References

- [Source: docs/planning-artifacts/epics.md#Story-5.1] — Story 5.1 completa (AC Given/When/Then), NFR-11
- [Source: docs/planning-artifacts/epics.md#Additional-Requirements] — dependencias de observabilidad ya listadas como parte del `pom.xml` inicial (actuator, micrometer-tracing/bridge-otel 1.4.4, springdoc — confirmado ya instaladas)
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-16] — Actuator con grupos liveness/readiness, puerto de management separado, traceId vía Micrometer Tracing + puente OpenTelemetry, logging JSON con traceId
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-11] — `/actuator/**` nunca en la lista pública de `SecurityConfig`, expuesto solo en puerto/interfaz de management separado
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Stack] — versiones verificadas: micrometer-tracing-bridge-otel/opentelemetry-exporter-otlp 1.4.4, Spring Boot 3.4.4
- [Source: src/main/resources/application.properties:14-18] — comentario placeholder existente que esta historia resuelve
- [Source: src/main/java/com/auth_service/auth/config/SecurityConfig.java] — `PUBLIC_ENDPOINTS`, confirma que no necesita tocarse (aislamiento por puerto, no por regla de autorización)
- [Source: src/test/java/com/auth_service/auth/AuthServiceApplicationTests.java] — patrón base de test `@SpringBootTest(RANDOM_PORT)` + Testcontainers + `TestPropertySource` a reutilizar
- [Source: pom.xml:113-125] — dependencias de observabilidad ya presentes (actuator, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, micrometer-registry-prometheus)
- Spring Boot 3.4 structured logging (formato `logstash`/`ecs` nativo, sin `logstash-logback-encoder`): https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4/
- `@LocalManagementPort` / puerto de management separado en tests: `org.springframework.boot.test.web.server.LocalManagementPort` (Boot 3.4.x) — verificar en el IDE al implementar, la reorganización de paquetes de test-support es reciente y puede variar por patch version

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- **Desvío #1 — `SecurityConfig.java` sí necesitó cambios (contradice el Dev Notes original "aislamiento por puerto, no por regla de autorización").** Con `spring-boot-starter-security` en el classpath y `management.server.port` distinto de `server.port`, Spring Boot crea un contexto hijo para Actuator que sigue viendo el único `SecurityFilterChain` del contexto padre (el de negocio, deny-all) — sin una cadena dedicada, toda petición al puerto de management devuelve 401. Verificado empíricamente con `ActuatorHealthIntegrationTest` en rojo antes del fix. Solución: una segunda `@Bean SecurityFilterChain` (`actuatorSecurityFilterChain`) con `@Order(Ordered.HIGHEST_PRECEDENCE)`, `securityMatcher(new AntPathRequestMatcher("/actuator/**"))` + `permitAll()`. Esto **no** viola AD-11 ("`/actuator/**` nunca en la lista pública"): `PUBLIC_ENDPOINTS` de la cadena de negocio queda intacto; la exposición es una cadena de seguridad estructuralmente distinta, que es exactamente el mecanismo que AD-16 describe como "puerto/interfaz de management separado".
- **Desvío #2 — `EndpointRequest.toAnyEndpoint()` no sirvió como `securityMatcher`.** Con ese matcher, `readiness`/`liveness` funcionaban pero `/actuator/prometheus` seguía devolviendo 401 — resolución inconsistente de `PathMappedEndpoints` entre el contexto hijo de management y el padre. Se reemplazó por un matcher de ruta literal (`AntPathRequestMatcher("/actuator/**")`), uniforme para todos los endpoints.
- **Desvío #3 — el overload `securityMatcher(String...)` (equivalente ambiguo del anterior) rompió TODA la suite, no solo los tests de Actuator.** Ese overload intenta resolver un `MvcRequestMatcher`, que requiere el bean `mvcHandlerMappingIntrospector` — ausente en cualquier test con `spring.main.web-application-type=none` (p. ej. `RefreshTokenRepositoryAdapterTest`), tumbando la carga de contexto de Spring para esos tests con `UnsatisfiedDependencyException`. Detectado corriendo la suite completa (no solo los tests nuevos) tras el primer fix aparentemente exitoso — confirma que "las pruebas nuevas pasan" no basta como gate; hay que correr la suite completa antes de dar una tarea por cerrada. Fix: `AntPathRequestMatcher` explícito, que no depende de infraestructura de Spring MVC.
- **Desvío #4 — `/actuator/prometheus` no aparecía ni en el índice de Actuator (`/actuator`) pese a exposure include correcto y todas las dependencias presentes.** El `ConditionEvaluationReport` (`debug=true` temporal) mostró: `PrometheusMetricsExportAutoConfiguration... Did not match: @ConditionalOnEnabledMetricsExport management.defaults.metrics.export.enabled is considered false`. Causa raíz no determinada con certeza (no hay ninguna property ni variable de entorno en el proyecto que fije ese valor a `false` explícitamente); el fix empírico fue añadir `management.prometheus.metrics.export.enabled=true` a `application.properties`, verificado con el índice de Actuator mostrando el link `prometheus` y con contenido real (`http_server_requests_seconds_*`, métricas de Tomcat) tras el fix. **Riesgo residual a vigilar:** si esto reaparece en otra historia, revisar primero si alguna dependencia nueva trae su propia auto-configuración que sobreescribe `management.defaults.metrics.export.enabled`.
- **Desvío #5 — el AC #2 ("cada línea de log") no se podía verificar con `GET /v3/api-docs`.** Ese endpoint solo genera una línea de log una única vez por JVM (inicialización lazy de springdoc), haciendo el test flaky según el orden de ejecución (el segundo test del archivo no encontraba ningún evento capturado). Cambiado a `GET /api/v1/users/me` con `Authorization: Bearer invalid-token` — dispara `JwtAuthenticationFilter.log.warn(...)` de forma determinista en cada llamada.
- **Desvío #6 — no se pudo verificar el JSON de consola capturando `System.out` (como proponía la Task 4 original).** El `ConsoleAppender` de Logback cachea su `OutputStream` al arrancar; reasignar `System.out` después no lo afecta. Se usó en su lugar el `Encoder` real del appender `CONSOLE` (nombre por defecto de Boot) para codificar un evento capturado por un `ListAppender`, y se verificó que el resultado es JSON parseable — valida el mismo formato de salida real sin el truco de redirección frágil.
- Suite completa verificada en verde DOS veces tras resolver los desvíos anteriores (`./mvnw test`, con Docker) — la primera corrida completa (antes del Desvío #3) reveló la regresión en `RefreshTokenRepositoryAdapterTest`; la segunda, tras el fix, confirmó 0 errores.

### Completion Notes List

- Las 2 ACs implementadas y verificadas: AC #1 (puerto de management aislado + grupos liveness/readiness con `db` en readiness + `/actuator/**` inalcanzable como negocio — 404 en el puerto de negocio), AC #2 (traceId en MDC de cada log de una petición + salida JSON estructurada + `/actuator/prometheus` con métricas `http_server_requests_seconds`).
- El plan original de la historia asumía que el aislamiento por puerto bastaba para exponer Actuator sin tocar `SecurityConfig` — resultó incorrecto en la práctica con Spring Security en el classpath (ver Debug Log References, Desvíos #1-3). El fix final SÍ respeta la letra de AD-11 (`/actuator/**` nunca en `PUBLIC_ENDPOINTS`) mediante una segunda `SecurityFilterChain` dedicada.
- Suite completa verde: 354 tests (348 previos + 6 nuevos), 0 fallas, 0 errores (`./mvnw test` con Testcontainers vía Docker).
- Ningún archivo de `domain/`, `application/usecase/` ni ningún controller existente fue modificado, tal como anticipaban los Dev Notes.
- **Post code-review (2026-07-23):** tras aplicar los 4 patches y la decisión 1 (guard de arranque), suite completa reverificada con Docker real y las variables `OAUTH2_SUCCESS_REDIRECT_URI`/`OAUTH2_FAILURE_REDIRECT_URI` exportadas: **355 tests, 0 fallas, 0 errores** (incluye los 3 tests nuevos de `ManagementPortSafetyCheckTest`, los 3 casos adicionales de `actuatorIsNotReachableOnTheBusinessPort` parametrizado, y el nuevo `ActuatorHealthDbDownIntegrationTest`, que confirma empíricamente que readiness responde 503 y liveness sigue `UP` con Postgres caído).

### File List

**Nuevos:**
- `src/test/java/com/auth_service/auth/ActuatorHealthIntegrationTest.java`
- `src/test/java/com/auth_service/auth/StructuredLoggingIntegrationTest.java`
- `src/main/java/com/auth_service/auth/config/ManagementPortSafetyCheck.java` (Review Findings — decisión 1)
- `src/test/java/com/auth_service/auth/config/ManagementPortSafetyCheckTest.java` (Review Findings — decisión 1)
- `src/test/java/com/auth_service/auth/ActuatorHealthDbDownIntegrationTest.java` (Review Findings — patch, rama DB caída)

**Modificados:**
- `src/main/resources/application.properties` (bloque Actuator reemplazado: puerto de management, grupos de salud; + bloque nuevo de logging estructurado/tracing; + `management.prometheus.metrics.export.enabled=true`)
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java` (+`actuatorSecurityFilterChain`, cadena de seguridad dedicada al puerto de management — no anticipado en el plan original, ver Debug Log References)

## Change Log

- 2026-07-22: Implementación inicial completa (Tasks 1-5). Tres desvíos técnicos respecto al plan documentados en Debug Log References: (1) `SecurityConfig.java` necesitó una segunda `SecurityFilterChain` dedicada al puerto de management — el aislamiento por puerto por sí solo no basta con Spring Security en el classpath; (2)/(3) el matcher de esa cadena pasó de `EndpointRequest.toAnyEndpoint()` a `AntPathRequestMatcher("/actuator/**")` tras romper `/actuator/prometheus` y, en un intento intermedio, toda la suite de tests sin contexto web; (4) `/actuator/prometheus` requirió `management.prometheus.metrics.export.enabled=true` explícito, no solo el exposure include.
