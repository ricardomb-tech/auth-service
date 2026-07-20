# **Auth-Service** 🔐

![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4.4-6DB33F?logo=springboot)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?logo=postgresql)
![JWT](https://img.shields.io/badge/JWT-jjwt_0.13-000000?logo=jsonwebtokens)
![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200?logo=flyway)
![License](https://img.shields.io/badge/License-MIT-yellow)

*Microservicio de autenticación y autorización construido con Spring Boot 3 y Clean Architecture. Emite y valida JWT, soporta login federado (OAuth2) y expone una API REST protegida por defecto (deny-all).*

---

## 📌 Tabla de contenidos

1. [Descripción](#-descripción)
2. [Estado del proyecto](#-estado-del-proyecto)
3. [Arquitectura](#-arquitectura)
4. [Tecnologías](#-tecnologías)
5. [Estructura del proyecto](#-estructura-del-proyecto)
6. [Puesta en marcha](#-puesta-en-marcha)
7. [Configuración (variables de entorno)](#-configuración-variables-de-entorno)
8. [Endpoints de la API](#-endpoints-de-la-api)
9. [Testing](#-testing)
10. [Decisiones de diseño relevantes](#-decisiones-de-diseño-relevantes)
11. [Limitaciones conocidas / trabajo diferido](#-limitaciones-conocidas--trabajo-diferido)

---

## 📝 Descripción

**Auth-Service** centraliza la identidad y el acceso de otros servicios/clientes. Provee:

- Registro de cuentas con verificación de email (anti-enumeración).
- Login con credenciales (email + password) y emisión de **Access Token (JWT) + Refresh Token**.
- Renovación de tokens con **rotación** y **detección de reuso** (revoca toda la familia si un refresh token ya usado vuelve a presentarse).
- Logout idempotente.
- Consulta del propio perfil (`/api/v1/users/me`).
- **Login federado con Google y GitHub (OAuth2)**, con vinculación de identidad federada a la cuenta existente.
- **Recuperación de contraseña** por email (anti-enumeración, token de un solo uso) — en revisión, ver [estado del proyecto](#-estado-del-proyecto).
- Todas las rutas son privadas por defecto; lo público se declara explícitamente (deny-all).

---

## 🚦 Estado del proyecto

El desarrollo sigue un roadmap por épicas y historias (`docs/implementation-artifacts/`). Estado actual:

| Épica | Descripción | Estado |
|---|---|---|
| **Epic 1** | Fundaciones + identidad con credenciales (Clean Architecture, registro, verificación, login, refresh+rotación, logout, perfil propio) | ✅ **Completa** (Stories 1.1 → 1.7) |
| **Epic 2** | Login social (OAuth2) | ✅ **Completa** — Story 2.1 (Google) y Story 2.2 (GitHub) |
| **Epic 3** | Recuperación de contraseña + bloqueo por fuerza bruta | 🔄 En progreso — Story 3.1 (recuperación de contraseña) en revisión, Story 3.2 (bloqueo por fuerza bruta) en backlog |
| **Epic 4** | Administración de cuentas + auditoría | ⏳ Backlog |
| **Epic 5** | Observabilidad (salud, métricas, trazas) y resiliencia ante proveedores externos | ⏳ Backlog |
| **Epic 6** | Integración por eventos (outbox transaccional) | ⏳ Backlog |

> Este README se actualiza junto con cada historia que cambia de estado. La fuente de verdad para el estado historia-por-historia es siempre [`sprint-status.yaml`](docs/implementation-artifacts/sprint-status.yaml); si notas una discrepancia, ese archivo manda.

Detalle historia por historia disponible en [`docs/implementation-artifacts/`](docs/implementation-artifacts/) y el tracking vivo en [`sprint-status.yaml`](docs/implementation-artifacts/sprint-status.yaml).

---

## 🏛 Arquitectura

El código sigue **Clean Architecture** con tres capas y dependencias apuntando siempre hacia adentro (verificado con **ArchUnit** en CI):

```
domain/          → Modelos y reglas de negocio puras (Account, FederatedIdentity, RefreshToken...).
                   No depende de Spring ni de infraestructura.
application/     → Casos de uso (LoginUseCase, RefreshTokenUseCase, FederatedLoginUseCase...).
                   Orquestan domain + ports, sin conocer detalles de infraestructura.
infrastructure/  → Adaptadores concretos: controladores REST, JPA/PostgreSQL, JWT, email, OAuth2, Kafka.
```

Puntos de diseño destacados:

- **Deny-all por defecto**: toda ruta nace protegida; lo público (`/auth/**`, `/oauth2/**`, swagger) se lista explícitamente en `SecurityConfig`.
- **Stateless**: sin sesiones de servidor; el flujo OAuth2 usa un `AuthorizationRequestRepository` respaldado por cookie en vez de sesión HTTP.
- **Rotación de secreto JWT sin downtime**: par de secretos activo + anterior (`JWT_SECRET_CURRENT` / `JWT_SECRET_PREVIOUS`).
- **Rotación de refresh tokens con detección de reuso**: si un refresh token ya usado se vuelve a presentar, se revoca toda su familia de tokens.
- **Respuestas anti-enumeración**: registro, reenvío de verificación y login devuelven el mismo cuerpo exista o no la cuenta.
- **Errores uniformes**: `application/problem+json` (RFC 9457) tanto para errores de autenticación (filtro de seguridad) como de dominio/aplicación (`GlobalExceptionHandler`).
- **Esquema de BD propiedad de Flyway**: Hibernate solo valida (`ddl-auto=validate`), nunca genera DDL.

---

## 🛠 Tecnologías

| Categoría | Tecnologías |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.4.4 (Web, Security, Data JPA, OAuth2 Client, Validation, Mail, Actuator) |
| Autenticación | JWT (`jjwt` 0.13), Spring Security, OAuth2 (Google, GitHub) |
| Base de datos | PostgreSQL 15 + Flyway (migraciones versionadas) |
| Mensajería | Spring Kafka / Redpanda (preparado para outbox transaccional de Epic 6) |
| Email (dev) | MailHog (SMTP + UI de captura) |
| Observabilidad | Micrometer + OpenTelemetry (trazas), Prometheus (métricas), Actuator |
| Resiliencia | Resilience4j |
| Documentación API | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Mockito, Spring Security Test, Testcontainers, ArchUnit, JaCoCo |
| Build | Maven (`mvnw`) |

---

## 📂 Estructura del proyecto

```
src/main/java/com/auth_service/auth/
├── domain/
│   ├── model/         # Account, FederatedIdentity, RefreshToken, VerificationToken, Role...
│   ├── port/           # Interfaces que implementa infrastructure (repos, stores)
│   ├── exception/      # Excepciones de dominio
│   └── event/          # Eventos de dominio
├── application/
│   └── usecase/        # Un caso de uso por operación de negocio (Register, Login, Refresh,
│                        # Logout, FederatedLogin, OAuth2Exchange, GetOwnProfile...)
├── config/              # SecurityConfig, JwtProperties, AuthTokenProperties, OAuth2RedirectProperties
└── infrastructure/
    ├── controller/      # AuthController, UserController + DTOs + GlobalExceptionHandler
    └── adapters/
        ├── postgresql/  # Entidades JPA + repositorios (adaptan los ports de domain)
        ├── security/    # JwtAuthenticationFilter, hashing de passwords
        ├── oauth/        # Handlers de éxito/fallo OAuth2, cookie authorization repository
        ├── email/        # Envío de correos de verificación
        └── messaging/    # Publicación de eventos de dominio

src/main/resources/
├── application.properties        # Config base (perfiles-agnóstica)
├── application-dev.properties    # Defaults para docker-compose local
├── application-prod.properties   # Sin defaults — falla rápido si falta una variable
└── db/migration/                 # Migraciones Flyway (V1...V4)

docs/
├── planning-artifacts/    # Brief, PRD, arquitectura, épicas
└── implementation-artifacts/  # Una historia por archivo + sprint-status.yaml + deferred-work.md
```

---

## 🚀 Puesta en marcha

### Requisitos

- Java 21
- Maven 3.9+ (o usar `./mvnw`)
- Docker + Docker Compose (para PostgreSQL, MailHog y Redpanda)

### 1. Levantar dependencias

```bash
docker-compose up -d
```

Esto levanta:
- **PostgreSQL 15** (`localhost:5432`)
- **MailHog** — captura los correos de verificación en dev (UI en `http://localhost:8025`)
- **Redpanda** (broker compatible con Kafka, preparado para el outbox transaccional de Epic 6; no se consume todavía)

### 2. Configurar variables de entorno

```bash
cp .env.example .env
# editar .env con tus valores (ver sección de Configuración)
```

### 3. Ejecutar el servicio

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

La API queda disponible en `http://localhost:8080`. Swagger UI en `http://localhost:8080/swagger-ui.html`.

---

## ⚙ Configuración (variables de entorno)

Ver [`.env.example`](.env.example) para la lista completa. Resumen por bloque:

```properties
# Base de datos
DB_HOST=localhost
DB_PORT=5432
DB_NAME=auth_service
DB_USER=auth_service
DB_PASSWORD=auth_service

# JWT — par activo+anterior para rotar el secreto sin downtime
JWT_SECRET_CURRENT=change-me-to-a-256-bit-random-secret
JWT_SECRET_PREVIOUS=

# TTLs de tokens (auth.token.*, AD-10, NFR-7)
# verification-ttl=24h · refresh-ttl=7d · password-reset-ttl=1h (defaults, override si hace falta)

# OAuth2 — Google (Story 2.1) y GitHub (Story 2.2) implementados
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
OAUTH2_SUCCESS_REDIRECT_URI=http://localhost:3000/oauth2/success
OAUTH2_FAILURE_REDIRECT_URI=http://localhost:3000/oauth2/failure

# Administrador inicial (aprovisionamiento, Epic 4)
AUTH_ADMIN_EMAIL=
AUTH_ADMIN_PASSWORD=

# Email (dev usa MailHog)
MAIL_HOST=localhost
MAIL_PORT=1025

# Eventos de dominio (Redpanda, Epic 6)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

APP_BASE_URL=http://localhost:8080
```

> ⚠️ Nunca commitear un `.env` con secretos reales. En el perfil `prod` no hay valores por defecto: si falta una variable, el arranque debe fallar.

---

## 🌐 Endpoints de la API

### Autenticación (`/auth`) — público

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/auth/register` | Registra una cuenta y envía email de verificación. Respuesta idéntica exista o no el email (anti-enumeración). |
| `POST` | `/auth/verify` | Verifica la cuenta con el token recibido por email. |
| `POST` | `/auth/resend-verification` | Reenvía el email de verificación si aplica. |
| `POST` | `/auth/forgot-password` | 🔄 *En revisión (Story 3.1).* Envía un email con token de recuperación de contraseña. Respuesta idéntica exista o no la cuenta (anti-enumeración). |
| `POST` | `/auth/reset-password` | 🔄 *En revisión (Story 3.1).* Establece una nueva contraseña a partir de un token de recuperación válido y no consumido. |
| `POST` | `/auth/login` | Valida credenciales y emite Access Token (JWT, 15 min) + Refresh Token (7 días). |
| `POST` | `/auth/refresh` | Rota el Refresh Token y emite un nuevo par. Detecta reuso y revoca la familia completa si el token ya fue usado. |
| `POST` | `/auth/logout` | Revoca el Refresh Token. Idempotente. |
| `GET` | `/oauth2/authorization/google` | Inicia el flujo de login federado con Google (redirige a Google). |
| `GET` | `/oauth2/authorization/github` | Inicia el flujo de login federado con GitHub (redirige a GitHub). |
| `POST` | `/auth/oauth2/exchange` | Canjea el código de un solo uso emitido tras un login federado exitoso por Access+Refresh Token (evita exponer tokens en la URL de redirect). |

### Usuario (`/api/v1/users`) — requiere Access Token

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/v1/users/me` | Perfil de la cuenta autenticada: id, email, roles, estado, identidades federadas vinculadas, fecha de creación. |

Roles disponibles: `USER`, `ADMIN`. Estados de cuenta: `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`, `DISABLED`.

Todos los errores (autenticación, autorización o de dominio) se devuelven como `application/problem+json`.

---

## ✅ Testing

```bash
./mvnw test
```

Incluye tests unitarios, de integración (`@SpringBootTest` + MockMvc), arquitectura (ArchUnit, verifica el sentido de las dependencias entre capas) y reporte de cobertura (JaCoCo).

> Los tests de integración que usan **Testcontainers** requieren Docker corriendo; sin Docker se saltan automáticamente en vez de fallar.

---

## 📌 Decisiones de diseño relevantes

El detalle completo vive en `docs/planning-artifacts/architecture/`. Algunas decisiones que afectan directamente cómo se consume la API:

- **AD-3 / AD-19** — JWT firmado con HS256, rotación de secreto vía par activo+anterior.
- **AD-8** — Contrato de error unificado en Problem Details (RFC 9457).
- **AD-11** — Deny-all: cualquier ruta nueva nace protegida hasta que se declare explícitamente pública.
- **AD-15** — Eventos de dominio vía patrón Transactional Outbox (Redpanda ya levantado, consumo desde Epic 6).

---

## ⚠ Limitaciones conocidas / trabajo diferido

Este proyecto documenta explícitamente los gaps encontrados en cada revisión de código en [`docs/implementation-artifacts/deferred-work.md`](docs/implementation-artifacts/deferred-work.md), en vez de dejarlos implícitos. Algunos ejemplos vigentes:

- No hay rate limiting ni bloqueo por fuerza bruta todavía en `/auth/login` ni `/auth/forgot-password` (llega con Story 3.2).
- Existen canales laterales de timing entre "cuenta no existe" y "cuenta existe" en varios endpoints (mismo cuerpo de respuesta, latencia distinguible); ya van 5 apariciones del mismo patrón (Stories 1.2, 1.3, 1.4, 1.6, 3.1) — candidato fuerte a una historia dedicada de mitigación transversal en vez de seguir difiriéndolo caso por caso.
- El perfil `prod` no valida de forma fail-fast que las variables de entorno requeridas estén presentes con un mensaje claro.

Antes de tocar código en un área, conviene revisar si ya hay un ítem diferido relacionado en ese archivo.

---

## 🔄 Mantenimiento de este README

Este documento describe el estado **real** del proyecto, no el aspiracional — se actualiza en el mismo commit que cierra o avanza una historia. Al terminar una historia:

1. Actualizar la tabla de [Estado del proyecto](#-estado-del-proyecto) si cambió el estado de una épica o historia.
2. Añadir/editar la fila correspondiente en [Endpoints de la API](#-endpoints-de-la-api) si se agregó o modificó un endpoint.
3. Reflejar nuevas variables de entorno en [Configuración](#-configuración-variables-de-entorno) y en [`.env.example`](.env.example).
4. Si la historia cerró un ítem de [`deferred-work.md`](docs/implementation-artifacts/deferred-work.md), quitarlo de la lista de ejemplos vigentes de este README (el archivo completo siempre es la fuente de verdad).

Este repositorio es compatible con generadores de documentación como [Mintlify](https://mintlify.wiki/): la estructura en secciones con encabezados estables, la tabla de contenidos y las tablas de endpoints están pensadas para poder alimentar guías y referencia de API generadas automáticamente sin reescritura manual.
