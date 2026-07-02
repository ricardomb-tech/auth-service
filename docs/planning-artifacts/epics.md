---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md
  - docs/planning-artifacts/prds/prd-auth-service-2026-07-01/addendum.md
  - docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md
---

# auth-service - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for auth-service, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories. **Actualizado 2026-07-01** con la revisión enterprise del PRD (FR-13, FR-14, NFR-11..16) y del Architecture Spine (AD-12..AD-21): capas domain/application/infrastructure separadas, ArchUnit, eventos de dominio, observabilidad, resiliencia, versionado de API, rotación de secretos, auditoría y gate de calidad automatizado.

## Requirements Inventory

### Functional Requirements

FR-1: Un visitante puede crear una Cuenta con email y contraseña que cumplan la política de validación; la Cuenta nace `PENDING_VERIFICATION` con Rol `USER`; respuesta anti-enumeración.
FR-2: El titular puede activar su Cuenta consumiendo el Token de Verificación (un solo uso, con expiración y reenvío).
FR-3: El titular de una Cuenta `ACTIVE` puede iniciar sesión con email y contraseña y recibir Access Token + Refresh Token; error genérico ante credenciales inválidas.
FR-4: Un cliente puede canjear un Refresh Token vigente por un nuevo par con rotación; la reutilización de un token rotado revoca la Familia de Tokens completa.
FR-5: El titular puede cerrar sesión invalidando su Refresh Token y su Familia de Tokens.
FR-6: Un visitante puede autenticarse con Google o GitHub recibiendo el mismo par de tokens; creación de Cuenta `ACTIVE` o vinculación a Cuenta existente por email.
FR-7: El titular puede solicitar recuperación de contraseña y restablecerla con un token temporal de un solo uso; revoca todas las sesiones; anti-enumeración.
FR-8: El sistema registra cada intento de login fallido por Cuenta; el login exitoso reinicia el contador.
FR-9: El sistema bloquea temporalmente una Cuenta al alcanzar el umbral de intentos fallidos (5/15 min, configurable) y notifica por email.
FR-10: Una Cuenta autenticada puede consultar su propio perfil (`/api/v1/users/me`); 401 sin token válido.
FR-11: Un Administrador puede listar (paginado), consultar, desactivar/reactivar Cuentas y modificar Roles; 403 para `USER`; sin auto-desactivación.
FR-12: El sistema aprovisiona el primer Administrador desde variables de entorno en el primer arranque.
FR-13: Toda acción administrativa queda registrada en un Registro de Auditoría append-only, consultable por un Administrador.
FR-14: El sistema publica un Evento de Dominio ante cada cambio de estado significativo de una Cuenta, para que los Servicios Consumidores reaccionen sin polling (at-least-once, formato versionado).

### NonFunctional Requirements

NFR-1: Contraseñas solo como hash BCrypt; tokens de un solo uso y refresh tokens solo hasheados (SHA-256) en BD; prohibido loggear secretos (AD-5).
NFR-2: Respuestas de error uniformes RFC 7807 `application/problem+json` con mensajes anti-enumeración (AD-8).
NFR-3: p95 de login y refresh < 500 ms en entorno local (SM-4); sin degradar el costo de hashing (SM-C1).
NFR-4: Cobertura de pruebas ≥ 80% en lógica de dominio/aplicación (SM-5); suite de pruebas negativas de autorización al 100% (SM-2).
NFR-5: Seguridad deny-all por defecto; sesión stateless; CSRF deshabilitado; endpoints públicos listados explícitamente (AD-11).
NFR-6: Esquema de BD versionado exclusivamente con Flyway; `ddl-auto=validate` (AD-7).
NFR-7: Configuración por variables de entorno con `@ConfigurationProperties` tipadas (AD-10); ningún umbral/TTL hardcodeado.
NFR-8: Arranque local completo con `docker-compose up` (postgres + mailhog + broker de eventos) + perfil `dev`.
NFR-9: Envío de email fuera de la transacción, tras commit; el fallo de envío no revierte la operación (AD-9).
NFR-10: API documentada con OpenAPI (springdoc) consumible por un Servicio Consumidor en < 30 min (SM-6).
NFR-11: Observabilidad operacional — health/readiness/liveness, métricas y trazas distribuidas correlacionables por petición (AD-16).
NFR-12: Resiliencia ante Google/GitHub/SMTP caídos o lentos — circuit breaker + timeout con fallback explícito (AD-17).
NFR-13: Versionado de API sin ruptura silenciosa — recursos protegidos bajo `/api/v1/**` (AD-18).
NFR-14: Rotación de `JWT_SECRET` sin downtime mediante par activo+anterior (AD-19).
NFR-15: Registro de Auditoría append-only, protegido a nivel de rol de base de datos (AD-20).
NFR-16: Gate de calidad automatizado en CI — ArchUnit, cobertura, escaneo de vulnerabilidades bloquean el merge a `main` (AD-12, AD-21).

### Additional Requirements

- Sin starter template: proyecto greenfield ya inicializado (Spring Initializr base en el repo); la primera historia agrega dependencias faltantes al `pom.xml` (oauth2-client, jjwt 0.13.0, flyway, validation, mail, actuator, springdoc 2.8.17, micrometer-tracing/bridge-otel 1.4.4, resilience4j-spring-boot3 2.4.0, archunit-junit5 1.4.2, spring-kafka, testcontainers).
- Clean Architecture / hexagonal estricta: `domain/` (Java puro: model, event, port, exception) → `application/usecase/` (orquestación transaccional) → `infrastructure/` (controller, adapters/postgresql, adapters/email, adapters/oauth, adapters/messaging) → `config/` (composición) (AD-1, AD-12, AD-13).
- Regla de dependencia enforced con ArchUnit (`ArchitectureRulesTest`), parte del gate de build (AD-12, AD-21).
- Value Objects (`Email`, `HashedPassword`, `AccountId`) en `domain/model`, validan invariantes en construcción (AD-14).
- Punto único de emisión de tokens `TokenIssuer` (`application/usecase`) compartido por credenciales y OAuth2 (AD-2); JWT HS256 con claims fijas `sub/email/roles/iat/exp/iss` (AD-3).
- Refresh tokens opacos con `family_id`, `used_at`, `revoked_at` (AD-4); TTLs configurables (access 15 min, refresh 7 días, verificación 24 h, reset 1 h).
- Mutación de estado de Cuenta solo desde `application/usecase` (AD-6, AD-13); máquina de estados `PENDING_VERIFICATION → ACTIVE ⇄ LOCKED / → DISABLED`.
- Email detrás del port `EmailSender`, protegido por Resilience4j (AD-9, AD-17); adapters `LoggingEmailSender` (dev) y SMTP (prod).
- Domain Events + Transactional Outbox (`outbox_event`) + poller publicando a Kafka/Redpanda (AD-15).
- Auditoría append-only en `audit_log`, sin permisos UPDATE/DELETE para el rol de aplicación (AD-20).
- Observabilidad: Actuator (`/actuator/health` con grupos liveness/readiness), Micrometer + OpenTelemetry OTLP, logging JSON con `traceId` (AD-16).
- Convenciones: código/tablas en inglés, UUIDs, `Instant`/`timestamptz`, DTOs record; `/auth/**` y `/oauth2/**` sin versión, recursos bajo `/api/v1/**` (AD-18).
- Tests: unit sin Spring para `domain/`; `application/` con mocks de ports; integración con Testcontainers (PostgreSQL, Kafka); `spring-security-test`; ArchUnit para arquitectura.

### UX Design Requirements

No aplica — el servicio es API-first sin UI propia (Non-Goal del PRD); no existe documento UX.

### FR Coverage Map

FR-1: Epic 1 - Registro de cuenta
FR-2: Epic 1 - Verificación de email
FR-3: Epic 1 - Login con credenciales
FR-4: Epic 1 - Rotación de refresh tokens
FR-5: Epic 1 - Logout
FR-6: Epic 2 - Login social Google/GitHub
FR-7: Epic 3 - Recuperación de contraseña
FR-8: Epic 3 - Registro de intentos fallidos
FR-9: Epic 3 - Bloqueo temporal automático
FR-10: Epic 1 - Perfil propio
FR-11: Epic 4 - Gestión administrativa de cuentas
FR-12: Epic 4 - Aprovisionamiento del primer administrador
FR-13: Epic 4 - Auditoría de acciones administrativas
FR-14: Epic 6 - Publicación de eventos de dominio

## Epic List

### Epic 1: Identidad con credenciales — registro, verificación y sesiones JWT
Un usuario puede registrarse, verificar su email, iniciar sesión, mantener su sesión con refresh tokens rotados, cerrar sesión y consultar su perfil. Incluye las fundaciones del servicio (dependencias, Clean Architecture, esquema Flyway, seguridad deny-all, gate de calidad, Docker Compose) porque todas las historias comparten los mismos archivos core.
**FRs covered:** FR-1, FR-2, FR-3, FR-4, FR-5, FR-10

### Epic 2: Login social con un clic
Un usuario puede autenticarse con Google o GitHub sin crear contraseña, y su identidad federada queda vinculada a su Cuenta si ya existía.
**FRs covered:** FR-6

### Epic 3: Cuenta resiliente — recuperación y protección
Un usuario puede recuperar su contraseña de forma autoservicio y su Cuenta queda protegida automáticamente ante ataques de fuerza bruta.
**FRs covered:** FR-7, FR-8, FR-9

### Epic 4: Operación administrativa
Un Administrador existe desde el primer arranque, puede gestionar las Cuentas del sistema por API y toda su actividad queda auditada.
**FRs covered:** FR-11, FR-12, FR-13

### Epic 5: Confiabilidad operacional
Un Operador puede verificar la salud del servicio, observar métricas y trazas para diagnosticar incidentes, y el servicio se mantiene disponible aunque Google, GitHub o el proveedor de email fallen.
**FRs covered:** — (NFR-11, NFR-12)

### Epic 6: Integración por eventos de dominio
Un Servicio Consumidor puede reaccionar a cambios de estado de Cuenta (registro, bloqueo, desactivación, revocación de sesión) sin hacer polling, suscribiéndose a los Eventos de Dominio publicados por Auth-Service.
**FRs covered:** FR-14

## Epic 1: Identidad con credenciales — registro, verificación y sesiones JWT

Un usuario puede registrarse, verificar su email, iniciar sesión, mantener su sesión con refresh tokens rotados, cerrar sesión y consultar su perfil. Incluye las fundaciones del servicio porque todas las historias comparten los mismos archivos core (SecurityConfig, TokenIssuer, esquema).

### Story 1.1: Servicio ejecutable con Clean Architecture y gate de calidad

As a desarrollador del ecosistema,
I want arrancar el auth-service completo con un comando, con capas separadas y un pipeline que bloquea código que las viole,
So that tengo una base segura, bien estructurada y reproducible sobre la cual construir cada feature.

**Acceptance Criteria:**

**Given** un clon limpio del repositorio con Docker y Java 21
**When** ejecuto `docker-compose up -d` y `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
**Then** la aplicación arranca conectada a PostgreSQL con las migraciones Flyway aplicadas (`ddl-auto=validate`, NFR-6)
**And** el `pom.xml` incluye oauth2-client, jjwt 0.13.0, flyway, validation, mail, actuator, springdoc 2.8.17, micrometer-tracing/bridge-otel 1.4.4, resilience4j-spring-boot3 2.4.0, archunit-junit5 1.4.2 (test), spring-kafka y testcontainers
**And** existe la estructura de paquetes `domain/{model,event,port,exception}`, `application/usecase/`, `infrastructure/{controller,adapters}`, `config/` (AD-1, AD-12, AD-13)

**Given** el código fuente del proyecto
**When** corre el test ArchUnit `ArchitectureRulesTest` (parte de `mvn test`)
**Then** el build falla si algún tipo de `domain/` importa `application/`, `infrastructure/` o cualquier paquete `org.springframework.*`/`jakarta.persistence.*` (AD-1, AD-12)
**And** el build falla si algún tipo de `application/` importa `infrastructure/` (AD-12)

**Given** la aplicación en ejecución
**When** hago GET a cualquier ruta no listada como pública (p. ej. `/api/v1/users/me`)
**Then** recibo 401 con cuerpo `application/problem+json` (AD-8, AD-11)
**And** la documentación OpenAPI en `/swagger-ui.html` y `/v3/api-docs` responde 200 (NFR-10)
**And** la sesión es stateless y CSRF está deshabilitado (NFR-5)

**Given** un pipeline de CI configurado en `.github/workflows/ci.yml`
**When** se abre un pull request
**Then** el pipeline ejecuta build, tests (incluye ArchUnit), reporta cobertura JaCoCo y bloquea el merge si la cobertura de `domain/`+`application/` cae bajo 80% o si algún test falla (NFR-16, AD-21)

### Story 1.2: Registro de cuenta con email de verificación

As a visitante,
I want crear una Cuenta con mi email y una contraseña,
So that pueda acceder al ecosistema tras verificar mi identidad. (FR-1)

**Acceptance Criteria:**

**Given** un email no registrado y una contraseña que cumple la política (≥8, mayúscula, minúscula, dígito)
**When** hago POST `/auth/register`
**Then** `RegisterAccountUseCase` crea una Cuenta `PENDING_VERIFICATION` con Rol `USER`, usando los Value Objects `Email` y `HashedPassword` (AD-14) — nunca `String` crudo cruza hacia `application/`
**And** la contraseña se almacena solo como hash BCrypt (NFR-1)
**And** se genera un Token de Verificación (hash SHA-256 en BD, TTL 24 h) y se envía por email tras el commit (AD-5, AD-9)
**And** la migración de esta historia crea únicamente `accounts`, `account_roles` y `verification_tokens`

**Given** un email ya registrado
**When** hago POST `/auth/register` con ese email
**Then** recibo la misma respuesta genérica que un registro nuevo, sin revelar existencia (anti-enumeración, NFR-2)

**Given** una contraseña que incumple la política, o un email con formato inválido
**When** hago POST `/auth/register`
**Then** el Value Object `Email`/`HashedPassword` rechaza la construcción y recibo 400 `application/problem+json` con el detalle de la regla incumplida

### Story 1.3: Verificación de email y reenvío

As a titular de una Cuenta recién creada,
I want activar mi Cuenta con el enlace recibido por email,
So that pueda iniciar sesión. (FR-2)

**Acceptance Criteria:**

**Given** un Token de Verificación vigente
**When** lo consumo vía POST `/auth/verify`
**Then** `VerifyAccountUseCase` transiciona la Cuenta a `ACTIVE` (AD-6, AD-13) y el token queda consumido y no reutilizable

**Given** un token expirado, ya consumido o inexistente
**When** intento verificar
**Then** recibo error `application/problem+json` y la Cuenta permanece `PENDING_VERIFICATION`

**Given** una Cuenta `PENDING_VERIFICATION`
**When** solicito reenvío vía POST `/auth/resend-verification`
**Then** se emite un token nuevo, el anterior queda invalidado y la respuesta es genérica exista o no la Cuenta

### Story 1.4: Login con credenciales y emisión de tokens

As a titular de una Cuenta activa,
I want iniciar sesión con mi email y contraseña,
So that reciba credenciales para usar las aplicaciones del ecosistema. (FR-3)

**Acceptance Criteria:**

**Given** credenciales válidas de una Cuenta `ACTIVE`
**When** hago POST `/auth/login`
**Then** `LoginUseCase` invoca a `TokenIssuer` y recibo un Access Token JWT HS256 (claims `sub`, `email`, `roles`, `iat`, `exp`, `iss`; TTL 15 min) y un Refresh Token opaco (AD-2, AD-3, AD-13)
**And** el Refresh Token se persiste solo como hash SHA-256 con `family_id` y `expires_at` 7 días en la tabla `refresh_tokens` creada por esta historia (AD-4)
**And** todo token se emite exclusivamente vía `TokenIssuer` — ningún otro caso de uso construye JWTs (AD-2)

**Given** credenciales inválidas (email inexistente o contraseña errónea)
**When** hago POST `/auth/login`
**Then** recibo el mismo error genérico 401 en ambos casos (NFR-2)

**Given** una Cuenta `PENDING_VERIFICATION`, `LOCKED` o `DISABLED`
**When** intento iniciar sesión con credenciales correctas
**Then** la autenticación es rechazada

**Given** un Access Token válido
**When** llamo a un endpoint protegido con `Authorization: Bearer`
**Then** el filtro JWT autentica la petición sin estado de sesión (NFR-5)

### Story 1.5: Renovación con rotación y detección de reuso

As a aplicación cliente,
I want canjear el Refresh Token por un par nuevo,
So that el usuario mantenga su sesión sin reautenticarse. (FR-4)

**Acceptance Criteria:**

**Given** un Refresh Token vigente y no usado
**When** hago POST `/auth/refresh`
**Then** `RefreshTokenUseCase` emite un nuevo par Access+Refresh de la misma Familia de Tokens vía `TokenIssuer` y el token canjeado queda marcado `used_at` (AD-4, AD-13)

**Given** un Refresh Token ya rotado (`used_at` no nulo)
**When** intento canjearlo de nuevo
**Then** toda la Familia de Tokens queda revocada y recibo 401
**And** los canjes posteriores de cualquier miembro de la familia fallan
**And** el evento se registra en logs estructurados con `accountId` y `traceId`, sin exponer el token (NFR-1, NFR-11)

**Given** un Refresh Token expirado o revocado
**When** intento canjearlo
**Then** recibo 401 y se requiere login completo

### Story 1.6: Logout

As a titular con sesión activa,
I want cerrar sesión,
So that mis credenciales de renovación queden inutilizables. (FR-5)

**Acceptance Criteria:**

**Given** un Refresh Token vigente
**When** hago POST `/auth/logout` con él
**Then** `LogoutUseCase` revoca su Familia de Tokens completa (AD-4, AD-13)
**And** el canje posterior de ese Refresh Token responde 401
**And** el Access Token vigente sigue siendo válido hasta su expiración natural (Non-Goal del PRD)

### Story 1.7: Perfil propio

As a titular autenticado,
I want consultar mis propios datos,
So that las aplicaciones muestren mi identidad, roles y estado. (FR-10)

**Acceptance Criteria:**

**Given** una petición GET `/api/v1/users/me` con Access Token válido
**When** el filtro JWT la autentica y `GetOwnProfileUseCase` la resuelve
**Then** recibo email, Roles, estado, Identidades Federadas y fechas de mi propia Cuenta, nunca de otra (AD-18)

**Given** una petición sin token, con token inválido o expirado
**When** llamo a GET `/api/v1/users/me`
**Then** recibo 401 `application/problem+json`

## Epic 2: Login social con un clic

Un usuario puede autenticarse con Google o GitHub sin crear contraseña, y su identidad federada queda vinculada a su Cuenta si ya existía.

### Story 2.1: Login con Google

As a visitante,
I want continuar con mi cuenta de Google,
So that acceda sin crear ni recordar otra contraseña. (FR-6)

**Acceptance Criteria:**

**Given** un visitante sin Cuenta previa
**When** completa el flujo `/oauth2/authorization/google` con éxito
**Then** `FederatedLoginUseCase` crea una Cuenta `ACTIVE` con Rol `USER`, sin contraseña local, con Identidad Federada `google` en la tabla `federated_identities` creada por esta historia (AD-13)
**And** recibe el mismo par Access+Refresh emitido por `TokenIssuer` que en el login con credenciales (AD-2)

**Given** una Cuenta local existente con el mismo email verificado por Google
**When** completa el login con Google
**Then** la Identidad Federada se vincula a la Cuenta existente sin duplicarla
**And** los logins posteriores por credenciales o Google acceden a la misma Cuenta

**Given** un fallo o cancelación en Google
**When** el flujo retorna con error
**Then** no se crea estado parcial y el error es controlado (AD-8)

### Story 2.2: Login con GitHub

As a visitante,
I want continuar con mi cuenta de GitHub,
So that acceda con la identidad que ya uso como desarrollador. (FR-6)

**Acceptance Criteria:**

**Given** un visitante sin Cuenta previa
**When** completa el flujo `/oauth2/authorization/github` con éxito
**Then** `FederatedLoginUseCase` crea una Cuenta `ACTIVE` con Identidad Federada `github`, reutilizando el mismo handler y `TokenIssuer` de la Story 2.1 (AD-2)

**Given** una Cuenta existente con el mismo email
**When** completa el login con GitHub
**Then** la identidad se vincula a la Cuenta existente y ambas vías acceden a la misma Cuenta

**Given** una Cuenta creada solo por vía federada
**When** su titular quiere contraseña local
**Then** solo puede definirla mediante el flujo de recuperación (FR-7, Epic 3)

## Epic 3: Cuenta resiliente — recuperación y protección

Un usuario puede recuperar su contraseña de forma autoservicio y su Cuenta queda protegida automáticamente ante ataques de fuerza bruta.

### Story 3.1: Recuperación de contraseña

As a titular que olvidó su contraseña,
I want restablecerla mediante un enlace temporal a mi email,
So that recupere el acceso sin intervención de nadie. (FR-7)

**Acceptance Criteria:**

**Given** una solicitud POST `/auth/forgot-password` con cualquier email
**When** `RequestPasswordResetUseCase` la procesa
**Then** la respuesta es idéntica exista o no la Cuenta (anti-enumeración, NFR-2)
**And** si la Cuenta existe se envía un Token de Verificación `PASSWORD_RESET` (hash en BD, TTL 1 h, un solo uso) tras el commit (AD-5, AD-9)

**Given** un token de recuperación vigente y una contraseña nueva que cumple la política
**When** hago POST `/auth/reset-password`
**Then** `ResetPasswordUseCase` actualiza la contraseña (`HashedPassword`, AD-14), el token queda consumido
**And** todas las Familias de Tokens de la Cuenta quedan revocadas (cierra sesiones en otros dispositivos, AD-4)

**Given** un token expirado o ya consumido
**When** intento restablecer
**Then** recibo error y la contraseña anterior sigue vigente

### Story 3.2: Bloqueo automático por fuerza bruta

As a titular de una Cuenta,
I want que el sistema bloquee mi Cuenta ante intentos repetidos de acceso fallidos,
So that un atacante no pueda adivinar mi contraseña. (FR-8, FR-9)

**Acceptance Criteria:**

**Given** intentos de login fallidos consecutivos contra una Cuenta
**When** cada intento falla dentro de `LoginUseCase`
**Then** el contador `failed_attempts` de la Cuenta se incrementa (FR-8)
**And** un login exitoso lo reinicia a cero

**Given** una Cuenta que alcanza 5 intentos fallidos dentro de la ventana
**When** ocurre el quinto fallo
**Then** la Cuenta pasa a `LOCKED` con `locked_until` = ahora + 15 min (umbral y duración configurables por entorno, NFR-7)
**And** se notifica al titular por email tras el commit (AD-9)

**Given** una Cuenta `LOCKED`
**When** se intenta login incluso con la contraseña correcta
**Then** la respuesta es el mismo error genérico 401 (sin señal al atacante, NFR-2)

**Given** una Cuenta `LOCKED` cuyo `locked_until` ya pasó
**When** se intenta un login válido
**Then** la Cuenta vuelve a operar como `ACTIVE` automáticamente (transición dentro de `LoginUseCase`, AD-6, AD-13)

## Epic 4: Operación administrativa

Un Administrador existe desde el primer arranque, puede gestionar las Cuentas del sistema por API y toda su actividad queda auditada.

### Story 4.1: Aprovisionamiento del primer administrador

As a operador del servicio,
I want que el primer arranque cree la Cuenta ADMIN inicial desde configuración,
So that nunca tenga que tocar la base de datos a mano. (FR-12)

**Acceptance Criteria:**

**Given** un arranque sin ninguna Cuenta con Rol `ADMIN`
**When** la aplicación inicia con `AUTH_ADMIN_EMAIL` y `AUTH_ADMIN_PASSWORD` definidos
**Then** se crea una Cuenta `ACTIVE` con Roles `ADMIN` y `USER` y contraseña BCrypt (AD-10, NFR-7)

**Given** un arranque donde ya existe al menos un `ADMIN`
**When** la aplicación inicia
**Then** no se crea ni modifica ninguna Cuenta

**Given** un arranque sin las variables definidas y sin `ADMIN` existente
**When** la aplicación inicia
**Then** se registra una advertencia clara en logs y el servicio arranca igualmente

### Story 4.2: Gestión administrativa de cuentas con auditoría

As a Administrador,
I want listar, consultar, desactivar/reactivar Cuentas y modificar Roles, con rastro verificable de cada acción,
So that opere el sistema sin acceso directo a la base de datos y pueda responder "quién hizo qué". (FR-11, FR-13)

**Acceptance Criteria:**

**Given** un Access Token con rol `ADMIN`
**When** hago GET `/api/v1/admin/accounts`
**Then** recibo el listado paginado de Cuentas (AD-18)
**And** GET `/api/v1/admin/accounts/{id}` devuelve el detalle

**Given** un Access Token con rol `USER`
**When** llamo a cualquier endpoint `/api/v1/admin/**`
**Then** recibo 403 `application/problem+json` (AD-11, NFR-4)

**Given** una Cuenta objetivo `ACTIVE`
**When** el Administrador la desactiva vía `ManageAccountUseCase`
**Then** pasa a `DISABLED`, todas sus Familias de Tokens quedan revocadas (AD-4) y no puede autenticarse por ninguna vía (credenciales ni OAuth2)
**And** en la misma transacción se inserta una fila en `audit_log` con actor, acción, Cuenta afectada, resultado y timestamp (AD-20, FR-13)
**And** la reactivación la devuelve a `ACTIVE` y también queda auditada

**Given** el propio Administrador autenticado
**When** intenta desactivarse o quitarse el Rol `ADMIN` a sí mismo
**Then** la operación es rechazada con error explícito, y el intento rechazado también se audita

### Story 4.3: Consulta del Registro de Auditoría

As a Administrador,
I want consultar el historial de acciones administrativas,
So that pueda investigar qué pasó con una Cuenta o quién ejecutó un cambio. (FR-13)

**Acceptance Criteria:**

**Given** un Access Token con rol `ADMIN`
**When** hago GET `/api/v1/admin/audit-log?accountId={id}`
**Then** recibo las entradas de auditoría donde esa Cuenta fue el objetivo, ordenadas por fecha descendente

**Given** un Access Token con rol `ADMIN`
**When** hago GET `/api/v1/admin/audit-log?from={fecha}&to={fecha}`
**Then** recibo las entradas dentro del rango

**Given** cualquier intento de modificar o borrar una entrada existente por cualquier vía de la API
**When** se intenta
**Then** no existe ningún endpoint que lo permita, y el rol de aplicación de PostgreSQL no tiene permiso `UPDATE`/`DELETE` sobre `audit_log` (AD-20)

## Epic 5: Confiabilidad operacional

Un Operador puede verificar la salud del servicio, observar métricas y trazas para diagnosticar incidentes, y el servicio se mantiene disponible aunque Google, GitHub o el proveedor de email fallen.

### Story 5.1: Salud, métricas y trazas distribuidas

As a Operador,
I want ver el estado de salud del servicio y sus métricas, y seguir una traza por petición,
So that pueda diagnosticar un incidente sin adivinar. (NFR-11)

**Acceptance Criteria:**

**Given** el servicio corriendo
**When** consulto `/actuator/health/readiness` y `/actuator/health/liveness` en el puerto de management
**Then** reflejan con precisión si el servicio puede recibir tráfico y si está vivo, incluyendo el estado de la conexión a PostgreSQL
**And** ninguno de los endpoints de `/actuator/**` aparece en la superficie pública de negocio (AD-11, AD-16)

**Given** el servicio corriendo con el exportador OTLP configurado
**When** hago una petición HTTP cualquiera
**Then** se genera/propaga un `traceId` (Micrometer Tracing + puente OpenTelemetry) presente en cada línea de log JSON asociada a esa petición (AD-16)
**And** `/actuator/prometheus` expone métricas de latencia y conteo por endpoint

### Story 5.2: Resiliencia ante proveedores externos

As a Operador,
I want que una caída de Google, GitHub o el proveedor de email no cuelgue ni degrade el resto del servicio,
So that un incidente externo no se convierta en un incidente propio. (NFR-12)

**Acceptance Criteria:**

**Given** el proveedor Google OAuth2 no responde dentro del timeout configurado
**When** un visitante intenta login social (Story 2.1)
**Then** el `CircuitBreaker`/`TimeLimiter` de Resilience4j devuelve un error controlado en vez de colgar la petición, y el resto del servicio (login con credenciales, refresh) sigue operando con normalidad (AD-17)

**Given** fallos consecutivos hacia el proveedor SMTP superan el umbral configurado
**When** el circuito se abre
**Then** las operaciones que dependen de email (registro, recuperación, aviso de bloqueo) siguen completándose (AD-9) pero el envío falla rápido en vez de bloquear la transacción, y el estado del circuito es visible como métrica (AD-16, AD-17)

**Given** el proveedor externo se recupera
**When** pasa el tiempo de espera del circuito
**Then** el circuito vuelve a cerrarse automáticamente y las llamadas se reintentan con normalidad

## Epic 6: Integración por eventos de dominio

Un Servicio Consumidor puede reaccionar a cambios de estado de Cuenta sin hacer polling, suscribiéndose a los Eventos de Dominio publicados por Auth-Service. Extiende los casos de uso de los Epics 1, 3 y 4 (dependencia hacia atrás, no requiere Epic 5).

### Story 6.1: Outbox transaccional y primer evento publicado

As a Servicio Consumidor,
I want que Auth-Service publique un evento cuando se registra una nueva Cuenta,
So that pueda reaccionar (por ejemplo, crear un perfil asociado) sin sondear la API. (FR-14)

**Acceptance Criteria:**

**Given** un registro exitoso (Story 1.2)
**When** `RegisterAccountUseCase` confirma la transacción
**Then** en la misma transacción se inserta una fila `AccountRegistered` en `outbox_event` con el payload versionado (AD-15)

**Given** filas pendientes en `outbox_event` (`published_at` nulo)
**When** el `OutboxPoller` (`infrastructure/adapters/messaging`) se ejecuta
**Then** publica cada evento al tópico correspondiente en Kafka/Redpanda y marca `published_at`
**And** si el broker no está disponible, el evento permanece pendiente y se reintenta en el siguiente ciclo — no se pierde (SM-8)

**Given** un registro que termina revertido por cualquier error dentro de la misma transacción
**When** la transacción hace rollback
**Then** no queda ninguna fila en `outbox_event` para ese intento (consistencia evento↔operación)

### Story 6.2: Cobertura completa de eventos de cambio de estado

As a Servicio Consumidor,
I want recibir también los eventos de verificación, bloqueo, desactivación y revocación de sesión,
So that pueda mantener sincronizado su propio estado local de la Cuenta sin polling. (FR-14)

**Acceptance Criteria:**

**Given** el mecanismo de outbox de la Story 6.1 ya en funcionamiento
**When** `VerifyAccountUseCase` (1.3), `LoginUseCase` al bloquear (3.2), `ManageAccountUseCase` al desactivar (4.2), o cualquier caso de uso que revoque una Familia de Tokens (1.4, 1.5, 1.6, 3.1) completan su transacción
**Then** cada uno inserta su evento correspondiente (`AccountVerified`, `AccountLocked`, `AccountDisabled`, `TokenFamilyRevoked`) en `outbox_event`, reutilizando el mismo publicador

**Given** un Servicio Consumidor suscrito al tópico
**When** recibe un evento duplicado (reintento del poller)
**Then** el payload incluye un identificador de evento estable que permite al consumidor deduplicar (at-least-once, Out of Scope de exactly-once end-to-end — PRD FR-14)
