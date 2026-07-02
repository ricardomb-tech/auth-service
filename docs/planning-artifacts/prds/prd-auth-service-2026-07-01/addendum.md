# Addendum — PRD Auth-Service

Profundidad técnica que no pertenece al PRD (capacidades) pero sí al trabajo downstream (arquitectura, solution design).

## Decisiones/preferencias técnicas ya existentes en el proyecto

- **Stack fijado por el repo:** Spring Boot 3.4.4, Java 21, Maven, Lombok, spring-boot-starter-{web,security,data-jpa}, spring-security-test. El README declara además PostgreSQL 15+, JWT vía `io.jsonwebtoken` (jjwt 0.12.x) y OAuth2 Client (dependencias aún no agregadas al pom — trabajo de la primera épica).
- **Estructura de paquetes declarada en el README (hexagonal ligera):**
  - `config/` — Spring Security, JWT, OAuth2, beans.
  - `domain/` — modelos (User, Role) y servicios de autenticación.
  - `infrastructure/adapters/postgreSQL/` — entidades JPA y repositorios.
  - `infrastructure/controller/` — endpoints REST.
- **Endpoints esbozados en el README:** `POST /auth/register`, `POST /auth/login`, `GET /api/users/me`, flujo `GET /oauth2/google`.
- **Config por variables de entorno:** DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD, JWT_SECRET, GOOGLE_CLIENT_ID/SECRET, GITHUB_CLIENT_ID/SECRET.
- **Despliegue:** Docker Compose (PostgreSQL + servicio); perfil `dev` para ejecución local.

## Parámetros sugeridos para el arquitecto (no vinculantes)

- Access Token TTL ~15 min; Refresh Token TTL ~7 días, rotación en cada canje, detección de reuso por familia (patrón "refresh token rotation with reuse detection").
- Hashing de contraseñas: BCrypt (delegating password encoder de Spring), cost factor por defecto.
- Tokens de verificación/recuperación: aleatorios de un solo uso, hasheados en BD, TTL 24 h (verificación) / 1 h (recuperación).
- Migraciones de esquema: Flyway.
- Email en dev: Mailhog o logging a consola detrás de una interfaz `EmailSender`.
- Firma JWT: pregunta abierta 2 del PRD — HS256 con secreto compartido es lo que sugiere el README (JWT_SECRET); el arquitecto debe evaluar RS256 + clave pública para los consumidores.

## Contexto descartado / alternativas consideradas

- **Keycloak / Auth0 / Cognito:** descartados en el brief — el objetivo es propiedad del stack, costo cero por MAU y valor de portafolio.
- **Access token revocation list:** descartado para MVP; se confía en TTL corto (Non-Goal del FR-5).
- **Rate limiting global:** se asume responsabilidad del gateway, fuera del servicio.

## Nivel enterprise — mecanismos técnicos (soportan NFR-11..16, FR-13, FR-14)

*Añadido en la revisión enterprise del 2026-07-01; ver `ARCHITECTURE-SPINE.md` AD-12..AD-21 para el detalle vinculante. Estos son los mecanismos concretos, no capacidades — el PRD solo exige el comportamiento observable.*

- **Separación de capas:** `domain/` Java puro sin Spring; `application/usecase/` concentra `@Transactional`; regla de dependencia enforced por ArchUnit (`archunit-junit5` 1.4.2) corriendo en cada build.
- **Eventos de dominio (FR-14):** patrón Transactional Outbox — la mutación y el registro del evento en `outbox_event` ocurren en la misma transacción; un poller separado publica a Kafka (compatible con Redpanda en dev). Formato de payload: pregunta abierta 6 del PRD (JSON simple vs. CloudEvents vs. Avro — decidir antes de la primera épica de eventos).
- **Observabilidad (NFR-11):** Spring Boot Actuator (`/actuator/health` con grupos `liveness`/`readiness`), Micrometer + `micrometer-tracing-bridge-otel` 1.4.4 exportando OTLP, logging JSON estructurado con `traceId` en MDC. Puerto de management separado del tráfico de negocio.
- **Resiliencia (NFR-12):** Resilience4j `resilience4j-spring-boot3` 2.4.0 — `@CircuitBreaker` + `@TimeLimiter` con fallback en los adapters que llaman a Google, GitHub y el proveedor SMTP.
- **Rotación de secretos (NFR-14):** validación de JWT acepta `JWT_SECRET_CURRENT` y `JWT_SECRET_PREVIOUS` simultáneamente durante la ventana de rotación; la firma usa solo el actual. Herramienta de secret store externo (Vault/AWS Secrets Manager/k8s Secrets) — pregunta abierta 5, no bloquea el desarrollo.
- **Auditoría (FR-13):** tabla `audit_log` sin permisos UPDATE/DELETE para el rol de aplicación de BD (se protege a nivel de rol de PostgreSQL, no solo de código).
- **Gate de calidad (NFR-16):** JaCoCo con umbral de cobertura de build, ArchUnit como test que puede romper el pipeline, escaneo de dependencias (OWASP dependency-check o equivalente) — todo como parte del pipeline CI, no como paso manual de code review.
