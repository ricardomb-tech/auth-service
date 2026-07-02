---
baseline_commit: d2f95cb77e2974ce438337b207931a8cac19cf81
---

# Story 1.1: Servicio ejecutable con Clean Architecture y gate de calidad

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a desarrollador del ecosistema,
I want arrancar el auth-service completo con un comando, con capas separadas y un pipeline que bloquea código que las viole,
so that tengo una base segura, bien estructurada y reproducible sobre la cual construir cada feature.

## Acceptance Criteria

1. Un clon limpio del repo arranca con `docker-compose up -d` + `mvn spring-boot:run -Dspring-boot.run.profiles=dev`: PostgreSQL conectado, migraciones Flyway aplicadas (`ddl-auto=validate`), `pom.xml` con todas las dependencias nuevas, y existe la estructura `domain/{model,event,port,exception}`, `application/usecase/`, `infrastructure/{controller,adapters}`, `config/`.
2. Un test ArchUnit (`ArchitectureRulesTest`, parte de `mvn test`) rompe el build si `domain/` importa `application/`, `infrastructure/` o cualquier paquete `org.springframework.*`/`jakarta.persistence.*`, o si `application/` importa `infrastructure/`.
3. Con la app corriendo, GET a cualquier ruta no pública (p. ej. `/api/v1/users/me`) responde 401 `application/problem+json`; `/swagger-ui.html` y `/v3/api-docs` responden 200; la sesión es stateless y CSRF está deshabilitado.
4. Un pipeline CI (`.github/workflows/ci.yml`) corre en cada PR: build + tests (incluye ArchUnit) + cobertura JaCoCo, y bloquea el merge si algún test falla o si la cobertura de `domain/`+`application/` cae bajo 80%.

## Tasks / Subtasks

- [x] Task 1: Actualizar `pom.xml` con las dependencias nuevas (AC: #1)
  - [x] `spring-boot-starter-oauth2-client`, `spring-boot-starter-validation`, `spring-boot-starter-mail`, `spring-boot-starter-actuator` (sin versión — gestionadas por `spring-boot-starter-parent` 3.4.4)
  - [x] `io.jsonwebtoken:jjwt-api:0.13.0`, `jjwt-impl:0.13.0` (runtime), `jjwt-jackson:0.13.0` (runtime)
  - [x] `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql` (sin versión, gestionadas por Boot)
  - [x] `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17`
  - [x] `io.micrometer:micrometer-tracing-bridge-otel:1.4.4`, `io.opentelemetry:opentelemetry-exporter-otlp` (sin versión explícita — BOM de Boot 3.4.4 la gestiona vía `micrometer-tracing-bridge-otel`; fijar 1.4.4 solo si el BOM no la trae)
  - [x] `io.github.resilience4j:resilience4j-spring-boot3:2.4.0`
  - [x] `com.tngtech.archunit:archunit-junit5:1.4.2` (scope `test`)
  - [x] `org.springframework.kafka:spring-kafka` (sin versión — gestionada por el BOM de Boot 3.4.4)
  - [x] `org.testcontainers:postgresql`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter` (scope `test`, sin versión — gestionadas por Boot)
  - [x] `org.postgresql:postgresql` (runtime, si no está ya vía `spring-boot-starter-data-jpa`)
  - [x] Configurar `maven-jacoco-plugin` con un goal `check` que falle el build si la cobertura de `domain/**` + `application/**` es menor a 80% (usar `<includes>` con esos paquetes; el resto del código queda fuera del umbral en esta historia)

- [x] Task 2: Crear la estructura de paquetes Clean Architecture bajo `com.auth_service.auth` (AC: #1, #2)
  - [x] `domain/model/` — vacío por ahora salvo un `package-info.java` o marcador; las entidades de dominio (`Account`, `Email`, `HashedPassword`, etc.) las crean las historias 1.2+
  - [x] `domain/event/`, `domain/port/`, `domain/exception/` — mismos, vacíos/marcador
  - [x] `application/usecase/` — vacío/marcador
  - [x] `infrastructure/controller/`, `infrastructure/adapters/postgresql/`, `infrastructure/adapters/email/`, `infrastructure/adapters/oauth/`, `infrastructure/adapters/messaging/` — vacíos/marcador
  - [x] `config/` — aquí sí va contenido real en esta historia (ver Task 3)
  - [x] No crear entidades de dominio, casos de uso ni controllers reales en esta historia — son responsabilidad de las historias 1.2 en adelante. Esta historia solo monta el esqueleto y las fundaciones transversales.

- [x] Task 3: Seguridad deny-all + manejo de errores RFC 7807 (AC: #3)
  - [x] `config/SecurityConfig.java`: `SecurityFilterChain` con `sessionManagement(STATELESS)`, `csrf().disable()`, `anyRequest().authenticated()`, y como públicas explícitas: `/error`, `/swagger-ui/**`, `/v3/api-docs/**` (`/auth/**` y `/oauth2/**` se añaden en historias posteriores cuando existan)
  - [x] **Desviación del plan original:** un `@RestControllerAdvice` NO intercepta `AuthenticationException`/`AccessDeniedException` cuando la petición nunca llega al dispatcher (caso típico de request no autenticada) — esas excepciones las lanza el filtro de seguridad, antes de Spring MVC. En su lugar, `SecurityConfig` configura un `AuthenticationEntryPoint` y un `AccessDeniedHandler` que escriben el `ProblemDetail` directamente en la respuesta vía `.exceptionHandling(...)`. No se creó `GlobalExceptionHandler` en esta historia — se creará cuando exista una excepción de dominio/aplicación real que capturar (Story 1.2+).
  - [x] Verificar `spring.security.filterchain` NO expone `/actuator/**` como público (Epic 5 lo configurará; por ahora Actuator no está expuesto en absoluto — no añadir su dependencia de exposición pública)

- [x] Task 4: Flyway + perfiles de configuración (AC: #1)
  - [x] `src/main/resources/db/migration/V1__init.sql` — puede ser un archivo vacío/con un comentario si ninguna historia posterior ha definido tablas aún; NO inventar tablas de `accounts` u otras aquí, eso es de la Story 1.2. Si Flyway exige al menos una migración no vacía, usar una tabla de metadatos trivial o un `CREATE EXTENSION IF NOT EXISTS "pgcrypto"` (necesario más adelante para generación de UUID) como contenido legítimo de V1.
  - [x] `application.properties`: `spring.jpa.hibernate.ddl-auto=validate`, `spring.flyway.enabled=true`
  - [x] `application-dev.properties`: apunta a Postgres/Mailhog locales vía las variables de entorno del README (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`)
  - [x] `application-prod.properties`: mismas claves, sin defaults hardcodeados

- [x] Task 5: OpenAPI (AC: #3)
  - [x] Configurar springdoc para exponer `/v3/api-docs` y `/swagger-ui.html` sin autenticación (ver Task 3)
  - [x] `config/OpenApiConfig.java` con un `Bean` mínimo de `OpenAPI` (título "Auth Service API", versión `v1`)

- [x] Task 6: ArchUnit (AC: #2)
  - [x] Crear `src/test/java/com/auth_service/auth/architecture/ArchitectureRulesTest.java`
  - [x] Regla 1: clases en `..domain..` no dependen de `..application..` ni `..infrastructure..`
  - [x] Regla 2: clases en `..domain..` no dependen de `org.springframework..` ni `jakarta.persistence..`
  - [x] Regla 3: clases en `..application..` no dependen de `..infrastructure..`
  - [x] Usar `com.tngtech.archunit.junit.AnalyzeClasses` sobre el paquete raíz `com.auth_service.auth`
  - [x] Este test debe PASAR trivialmente ahora mismo porque los paquetes de dominio/aplicación están vacíos — su valor es que a partir de la Story 1.2 cualquier violación futura rompe el build inmediatamente

- [x] Task 7: Docker Compose de desarrollo (AC: #1)
  - [x] `docker-compose.yml`: servicio `postgres` (imagen `postgres:15`, variables desde `.env`/valores por defecto de dev), servicio `mailhog` (`mailhog/mailhog`, puertos 1025/8025), servicio `redpanda` (imagen Redpanda single-node, puerto Kafka-compatible expuesto) — Redpanda se agrega en esta historia como infraestructura aunque no se use hasta la Story 6.1, porque `spring-kafka` en el classpath sin un broker accesible en `dev` rompería el arranque si algo lo referencia; si se prefiere no arrancarlo hasta que se use, documentar en Dev Notes por qué se omite y ajustar
  - [x] `.env.example` con las variables del README + las nuevas de esta arquitectura (no incluir secretos reales)

- [x] Task 8: Pipeline CI (AC: #4)
  - [x] `.github/workflows/ci.yml`: job que hace checkout, setup Java 21 (Temurin), `mvn -B verify` (incluye test + jacoco:check del Task 1), sube el reporte de cobertura como artifact
  - [x] El job debe fallar (exit code ≠ 0) si `mvn verify` falla — no se necesita lógica adicional, basta con no usar `continue-on-error`

### Review Findings

_Code review 2026-07-02 — Blind Hunter + Edge Case Hunter + Acceptance Auditor (paralelo, adversarial)._

- [x] [Review][Patch] Dev Agent Record dice "9/9 tests" pero el diff solo contiene 8 (`AuthServiceApplicationTests` x3 + `SecurityConfigTest` x2 + `ArchitectureRulesTest` x3) [docs/implementation-artifacts/1-1-servicio-ejecutable-con-clean-architecture-y-gate-de-calidad.md] — corregido a 8/8
- [x] [Review][Patch] `SecurityConfigTest.swaggerUiIsPublic` tolera un 404 como "público" — el nombre y el propósito del test prometen más de lo que verifica [src/test/java/com/auth_service/auth/config/SecurityConfigTest.java:538-547] — renombrado a `swaggerUiPathIsNotBlockedBySecurityFilter` con Javadoc explícito sobre su alcance real
- [x] [Review][Patch] `AuthServiceApplicationTests` falla duro (no se salta) cuando Docker no está disponible en una máquina de desarrollo — sin `disabledWithoutDocker` [src/test/java/com/auth_service/auth/AuthServiceApplicationTests.java:274-276] — añadido `@Testcontainers(disabledWithoutDocker = true)`
- [x] [Review][Patch] Redpanda fijado a la tag flotante `latest` en `docker-compose.yml` — build no reproducible [docker-compose.yml:583] — fijado a `v25.3.15`
- [x] [Review][Patch] Ejecución `jacoco-check` sin `<phase>` explícita (depende del binding implícito a `verify`) [pom.xml:186-208] — añadido `<phase>verify</phase>`
- [x] [Review][Patch] La convivencia entre `SecurityConfig` (Problem Details escritos a mano) y el futuro `GlobalExceptionHandler` no tiene nota que garantice que ambos formatos no diverjan [src/main/java/com/auth_service/auth/config/SecurityConfig.java] — añadida nota de "Contrato a mantener sincronizado" en el Javadoc de la clase

- [x] [Review][Defer] Docs OpenAPI públicas en prod sin gate por entorno [src/main/java/com/auth_service/auth/config/SecurityConfig.java] — deferred, pre-existing (ya es `[ASSUMPTION]` en AD-11 del spine, no lo introduce este diff)
- [x] [Review][Defer] `.env.example`/`JWT_SECRET_CURRENT` sin validación fail-fast contra el valor placeholder [.env.example] — deferred, pertenece a la historia que conecte la firma JWT (1.4+)
- [x] [Review][Defer] CI sin `timeout-minutes` en el job [.github/workflows/ci.yml] — deferred, mejora de robustez menor
- [x] [Review][Defer] AD-21(c) exige escaneo de vulnerabilidades de dependencias bloqueante; el CI de esta historia no lo tiene (el AC #4 tal como está escrito tampoco lo pide) [.github/workflows/ci.yml] — deferred, gap real entre spine y AC de la historia, requiere decidir si se amplía el AC o se trata en otra historia
- [x] [Review][Defer] `SecurityConfig`: escritura de respuesta sin guardas para `response.isCommitted()` [src/main/java/com/auth_service/auth/config/SecurityConfig.java:72-78] — deferred, edge case de baja probabilidad, sin AC que lo exija
- [x] [Review][Defer] `V1__init.sql` asume privilegio para `CREATE EXTENSION` [src/main/resources/db/migration/V1__init.sql] — deferred, válido en dev (superusuario en docker-compose); relevante solo si el Postgres de prod es gestionado sin superusuario
- [x] [Review][Defer] Perfil `prod` sin validación fail-fast de variables requeridas (mensaje genérico de placeholder no resuelto) [src/main/resources/application-prod.properties] — deferred, endurecimiento futuro
- [x] [Review][Defer] Test de `swagger-ui.html` no verifica el header `Location` del redirect [src/test/java/com/auth_service/auth/AuthServiceApplicationTests.java:292-295] — deferred, bajo valor

## Dev Notes

- **Paradigma:** Clean Architecture / hexagonal estricta. `domain/` es Java puro (sin Spring, sin JPA). La orquestación transaccional vive en `application/usecase/` (histórias futuras). `infrastructure/` son los adapters. `config/` es la raíz de composición. [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Design-Paradigm, AD-1, AD-12, AD-13]
- **Esta historia NO implementa ningún caso de uso ni entidad de dominio.** Es deliberadamente solo el esqueleto + fundaciones transversales (seguridad, errores, Flyway, docs, ArchUnit, CI). Las historias 1.2 en adelante son las que llenan `domain/model`, `application/usecase`, etc. No te adelantes creando `Account`, `Email`, etc. aquí — eso pertenece a la Story 1.2 y generaría archivos huérfanos sin AC que los cubra en esta historia.
- **ArchUnit es el corazón de esta historia** (AD-12): el test debe existir y debe poder fallar en el futuro, no solo pasar hoy porque los paquetes están vacíos. Verifica manualmente que si movieras una clase de prueba con un import de Spring a `domain/model/`, el test la detectaría — no hace falta commitear ese experimento, solo confirmar el comportamiento antes de dar la tarea por completada.
- **No inventes rutas de negocio todavía.** El único endpoint "real" que puede existir tras esta historia son los de Actuator (no expuestos aún, ver Task 3) y los de OpenAPI/Swagger. No crees `/auth/register` ni `/api/v1/users/me` — esos son de historias posteriores; el AC #3 solo pide que una ruta *hipotética* protegida (usa `/api/v1/users/me` como ejemplo en la prueba manual/test de integración) devuelva 401 gracias a `anyRequest().authenticated()`, no que el endpoint exista de verdad.
- **Migración V1 vacía/mínima:** no hay tablas de negocio que crear en esta historia (esas llegan con la Story 1.2: `accounts`, `account_roles`, `verification_tokens`). Si Flyway requiere contenido no trivial, usar `CREATE EXTENSION IF NOT EXISTS "pgcrypto";` que de todos modos hará falta pronto para generación de UUID en PostgreSQL.
- **Testing standards:** JUnit 5 puro para `domain/` y `application/` (aún no hay nada que testear ahí en esta historia salvo, opcionalmente, un test trivial de contexto). Integración con `@SpringBootTest` + Testcontainers PostgreSQL para validar que el contexto de Spring levanta con la config de seguridad y Flyway. ArchUnit para la regla de dependencias. [Source: ARCHITECTURE-SPINE.md#Consistency-Conventions, fila "Tests"]
- **Cobertura JaCoCo del 80%** (NFR-4) aplica formalmente a partir de que exista código real en `domain/`+`application/`; en esta historia, con esos paquetes vacíos, el gate debe configurarse pero no bloqueará por falta de contenido — confirma que el plugin no falla por "no hay clases que analizar" (usar `haltOnFailure` solo si hay violación real de umbral, no por ausencia de clases).

### Project Structure Notes

- Alineado con el árbol de `ARCHITECTURE-SPINE.md#Structural-Seed`: `src/main/java/com/auth_service/auth/{config,domain,application,infrastructure}` + `src/test/java/com/auth_service/auth/architecture/` para ArchUnit.
- El paquete raíz actual del proyecto es `com.auth_service.auth` (confirmado en `src/main/java/com/auth_service/auth/AuthServiceApplication.java`) — no renombrar.
- Variación respecto al README original: el README describía `infrastructure/adapters/postgreSQL/` (con mayúscula media) y controllers sin capa `application/` intermedia. Esta historia introduce la capa `application/usecase/` que el README no mencionaba — es la decisión deliberada de la revisión enterprise del spine (AD-13), no un error.

### References

- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Design-Paradigm] — diagrama de capas y su mapeo a paquetes
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-1] — dominio sin frameworks
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-12] — regla de dependencia enforced con ArchUnit
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-21] — gate de calidad en CI
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Stack] — versiones exactas de cada dependencia nueva
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Structural-Seed] — árbol de directorios objetivo y despliegue/entornos
- [Source: docs/planning-artifacts/epics.md#Story-1.1] — criterios de aceptación originales (Given/When/Then) de los que se derivaron los AC numerados arriba
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#5-Requisitos-No-Funcionales-Transversales-NFR] — NFR-5 (deny-all), NFR-6 (Flyway), NFR-10 (OpenAPI), NFR-16 (gate de calidad)
- Versiones verificadas en la web el 2026-07-01: jjwt 0.13.0, springdoc-openapi-starter-webmvc-ui 2.8.17, archunit-junit5 1.4.2, resilience4j-spring-boot3 2.4.0, micrometer-tracing-bridge-otel 1.4.4 — ver `docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md#Nivel-enterprise` para el detalle y las fuentes.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -B -q compile` — compilación limpia con todas las dependencias nuevas.
- `./mvnw -B -Dtest=ArchitectureRulesTest test` — 3/3 reglas ArchUnit en verde.
- Verificación manual de AD-12: se creó temporalmente `domain/model/TempViolation.java` con `@Component` (import de Spring) → `domain_should_be_framework_free` falló correctamente reportando la clase y la línea; se eliminó el archivo y se re-ejecutó el test, vuelve a verde. Confirma que la regla realmente bloquea, no solo pasa por casualidad.
- `./mvnw -B -Dtest=SecurityConfigTest test` — 2/2 en verde (401 + `application/problem+json` en ruta protegida; ruta pública no devuelve 401/403).
- Primera corrida de `./mvnw -B verify` con Docker Desktop recién iniciado: Testcontainers no lograba conectar (`Could not find a valid Docker environment`, respuesta HTTP 400 vacía del daemon) — causa raíz: Testcontainers 1.20.6 (versión que fija el BOM de Boot 3.4.4) es incompatible con Docker Engine 29.x en Windows ([testcontainers-java#11235](https://github.com/testcontainers/testcontainers-java/issues/11235), [#11422](https://github.com/testcontainers/testcontainers-java/issues/11422)). Fix: `<testcontainers.version>1.21.4</testcontainers.version>` en `pom.xml` (última patch 1.21.x en Maven Central a la fecha).
- Con Testcontainers arreglado, apareció un segundo fallo real: `PatternParseException: No more pattern data allowed after {*...} or ** pattern element` al registrar los recursos de swagger-ui — causa raíz: regresión de springdoc-openapi 2.8.15/2.8.16/2.8.17 con Spring Framework 6.2.5/Boot 3.4.4 ([springdoc/springdoc-openapi#3210](https://github.com/springdoc/springdoc-openapi/issues/3210)). Fix: downgrade a `springdoc-openapi-starter-webmvc-ui:2.8.14` (última versión sin la regresión).
- `./mvnw -B verify` final — **8/8 tests en verde** (ArchUnit x3, SecurityConfigTest x2, AuthServiceApplicationTests x3 con Testcontainers real: contexto completo + Flyway migró + `/v3/api-docs` 200 + `/swagger-ui.html` redirect), JaCoCo `check` pasa, `BUILD SUCCESS`.
- Corrección post-review: el conteo original decía "9/9" por error de suma (3+2+3=8, no 9) — corregido tras el hallazgo del Acceptance Auditor.

### Completion Notes List

- Las 4 AC están implementadas y verificadas end-to-end con `mvn verify` (8/8 tests, incluyendo el contexto completo de Spring contra PostgreSQL real vía Testcontainers).
- Dos incompatibilidades de terceros con Spring Boot 3.4.4 se descubrieron y corrigieron durante esta historia (ver Debug Log References): `testcontainers.version` fijado a `1.21.4` (Docker Engine 29.x) y `springdoc-openapi-starter-webmvc-ui` fijado a `2.8.14` en vez de `2.8.17` (regresión de patrones de ruta). Ambos quedaron documentados con comentario en `pom.xml` citando el issue upstream — revisar si versiones futuras del BOM de Boot resuelven esto y estos pins dejan de ser necesarios.
- Desviación documentada en Task 3 (ver checklist): manejo de errores de seguridad vía `AuthenticationEntryPoint`/`AccessDeniedHandler` en `SecurityConfig`, no vía `@RestControllerAdvice` (que no intercepta excepciones lanzadas por el filtro de seguridad antes del dispatcher).
- Se agregó `.env` a `.gitignore` (no estaba) para evitar el riesgo de commitear `JWT_SECRET`/credenciales reales una vez que alguien copie `.env.example` a `.env`.
- Redpanda se incluyó en `docker-compose.yml` desde esta historia (decisión confirmada explícitamente por el usuario), aunque no se consume hasta la Story 6.1.
- En Windows, Testcontainers necesita `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` explícito cuando Docker Desktop expone el pipe legado `docker_engine` que no resuelve al daemon real — de lo contrario falla incluso con Docker corriendo. Vale la pena documentarlo en el README de desarrollo si el equipo crece.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/config/SecurityConfig.java`
- `src/main/java/com/auth_service/auth/config/OpenApiConfig.java`
- `src/main/java/com/auth_service/auth/domain/{model,event,port,exception}/.gitkeep`
- `src/main/java/com/auth_service/auth/application/usecase/.gitkeep`
- `src/main/java/com/auth_service/auth/infrastructure/controller/.gitkeep`
- `src/main/java/com/auth_service/auth/infrastructure/adapters/{postgresql,email,oauth,messaging}/.gitkeep`
- `src/main/resources/db/migration/V1__init.sql`
- `src/main/resources/application-dev.properties`
- `src/main/resources/application-prod.properties`
- `src/test/java/com/auth_service/auth/architecture/ArchitectureRulesTest.java`
- `src/test/java/com/auth_service/auth/config/SecurityConfigTest.java`
- `docker-compose.yml`
- `.env.example`
- `.github/workflows/ci.yml`

**Modificados:**
- `pom.xml` (dependencias nuevas + plugin JaCoCo)
- `src/main/resources/application.properties` (Flyway, ddl-auto=validate, Actuator sin exponer)
- `src/test/java/com/auth_service/auth/AuthServiceApplicationTests.java` (Testcontainers + MockMvc, requiere Docker local)
- `.gitignore` (excluir `.env`)
