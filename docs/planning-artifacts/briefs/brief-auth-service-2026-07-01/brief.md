---
title: "Product Brief: Auth-Service"
status: draft
created: 2026-07-01
updated: 2026-07-01
---

# Product Brief: Auth-Service

## Resumen Ejecutivo

**Auth-Service** es un microservicio de autenticación y autorización construido con Spring Boot 3.4.4 y Java 21, diseñado para ser la puerta de entrada única de identidad en un ecosistema de microservicios. Emite y valida tokens JWT, gestiona roles (ADMIN/USER), ofrece inicio de sesión social vía OAuth2 (Google y GitHub) y persiste identidades en PostgreSQL.

El problema que resuelve es doble: cada servicio nuevo que se construye necesita autenticación segura, y reimplementarla en cada proyecto multiplica errores, inconsistencias y superficie de ataque. Centralizar la identidad en un servicio dedicado, con prácticas modernas (tokens de corta duración con refresh tokens rotados, verificación de email, bloqueo por fuerza bruta), convierte la seguridad de un riesgo repetido en una capacidad reutilizable.

[ASSUMPTION] El servicio nace como pieza de un portafolio/ecosistema personal de microservicios con intención de calidad profesional — no es un prototipo desechable, pero tampoco tiene aún tráfico de producción ni requisitos de compliance formales (SOC2, PCI).

## El Problema

- **Duplicación de esfuerzo:** cada API nueva requiere registro, login, gestión de contraseñas y control de acceso. Reimplementarlo por servicio es lento y propenso a errores.
- **Riesgo de seguridad:** la autenticación artesanal suele omitir controles críticos — hashing débil, tokens sin expiración, ausencia de bloqueo por intentos fallidos, secretos hardcodeados.
- **Fricción de usuario:** sin login social, el registro manual reduce conversión; sin recuperación de contraseña, cada olvido es una cuenta perdida o un ticket de soporte.
- **Costo del status quo:** hoy el proyecto es un esqueleto de Spring Boot sin lógica; cualquier servicio consumidor tendría que resolver identidad por su cuenta.

## La Solución

Un microservicio autónomo que expone una API REST de identidad:

- **Registro e inicio de sesión** con credenciales validadas y contraseñas hasheadas (BCrypt).
- **Emisión de JWT firmados** de corta duración + **refresh tokens con rotación** para sesiones prolongadas sin sacrificar seguridad.
- **Autorización por roles** (ADMIN, USER) consumible por otros servicios vía validación del token.
- **Login social OAuth2** con Google y GitHub, vinculando identidades federadas a cuentas locales.
- **Ciclo de vida de cuenta completo:** verificación de email al registrarse, recuperación de contraseña por enlace temporal y bloqueo automático tras intentos fallidos repetidos.
- **Persistencia en PostgreSQL** con esquema versionado.

## Qué Lo Hace Diferente

Honestamente: no compite con Auth0, Keycloak o Cognito en features. Su valor es otro:

- **Propiedad total del stack** — sin dependencia de SaaS externo, sin costos por MAU, datos de usuarios bajo control propio.
- **Ligero y enfocado** — hace identidad y nada más; Keycloak resuelve esto pero trae un costo operativo y curva de aprendizaje desproporcionados para un ecosistema pequeño.
- **Demostración de dominio técnico** [ASSUMPTION: objetivo de portafolio] — implementa el estándar de la industria (JWT + refresh rotation, OAuth2, lockout) con Spring Security idiomático.

## A Quién Sirve

- **Desarrollador del ecosistema (primario):** necesita que sus otros microservicios deleguen identidad con un `Authorization: Bearer` y una validación de firma. Éxito = integrar un servicio nuevo en minutos.
- **Usuario final de las apps consumidoras:** quiere registrarse en segundos (o con un clic vía Google/GitHub), no perder su cuenta y que sus credenciales estén seguras. Éxito = login < 2s, recuperación de contraseña autoservicio.
- **Administrador:** necesita rol diferenciado para operaciones privilegiadas (gestión de usuarios). [ASSUMPTION] La gestión administrativa de usuarios (listar, desactivar) entra en el alcance como capacidad mínima del rol ADMIN.

## Criterios de Éxito

- Todos los endpoints protegidos rechazan tokens inválidos/expirados (0 bypasses en pruebas).
- Flujo completo registro → verificación email → login → refresh → logout funcional de extremo a extremo.
- Login social Google y GitHub operativo con vinculación de cuenta.
- Cuenta bloqueada automáticamente tras N intentos fallidos [ASSUMPTION: 5 intentos / ventana 15 min].
- Cobertura de pruebas de la lógica de seguridad ≥ 80% [ASSUMPTION].
- Arranque local con `docker-compose up` en un solo comando.

## Alcance

**Dentro (MVP):**
- Registro/login con email+contraseña, JWT access + refresh tokens con rotación.
- Roles ADMIN/USER y protección de endpoints.
- OAuth2 Google y GitHub.
- Verificación de email, recuperación de contraseña, bloqueo por intentos fallidos.
- Endpoint `/api/users/me` y gestión mínima de usuarios para ADMIN.
- PostgreSQL + Docker Compose.

**Fuera (explícitamente):**
- MFA/TOTP, passkeys/WebAuthn.
- Multi-tenancy y organizaciones.
- UI propia de login (el servicio es API-first; las apps consumidoras renderizan sus formularios). [ASSUMPTION]
- Federación SAML/LDAP, SSO empresarial.
- Rate limiting a nivel gateway (se asume infraestructura aparte).

## Visión

Si funciona, Auth-Service se convierte en el proveedor de identidad estándar de todo el ecosistema: nuevos microservicios se integran solo validando JWT, se agrega MFA y passkeys como evolución natural, y el servicio escala horizontalmente detrás de un gateway. En 2-3 años es la pieza de infraestructura que ningún proyecto del portafolio vuelve a reimplementar.
