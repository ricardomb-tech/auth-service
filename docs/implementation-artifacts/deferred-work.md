# Deferred Work

Items surfaced during code review or implementation that are real but out of scope for the story that found them. Revisit when the referenced story/epic lands, or sooner if they start causing pain.

## Deferred from: code review of story 1-1-servicio-ejecutable-con-clean-architecture-y-gate-de-calidad (2026-07-02)

- **Docs OpenAPI públicas en prod sin gate por entorno** — `SecurityConfig` lista `/swagger-ui/**` y `/v3/api-docs/**` como públicas incondicionalmente. Ya es un `[ASSUMPTION]` explícito en AD-11 del architecture spine, no introducido por esta historia. Revisar cuando se defina la topología de producción.
- **`.env.example`/`JWT_SECRET_CURRENT` sin validación fail-fast contra el valor placeholder** — nada rechaza que el servicio arranque en `prod` con el literal `change-me-to-a-256-bit-random-secret`. Pertenece a la historia que conecte la firma/validación JWT (Epic 1, Story 1.4+).
- **CI sin `timeout-minutes` en el job** — un pull de imagen colgado podría bloquear el workflow hasta el límite por defecto de GitHub (6h). Mejora de robustez menor, no bloqueante.
- **AD-21(c) exige escaneo de vulnerabilidades de dependencias bloqueante; el CI actual no lo tiene** — el AC #4 de la Story 1.1, tal como está escrito, tampoco lo pide explícitamente, así que no es una violación de AC, pero sí un gap real frente al spine. Decidir: ampliar un AC futuro para incluirlo, o crear una historia/task dedicada (ej. OWASP dependency-check o Dependabot).
- **`SecurityConfig` sin guarda para `response.isCommitted()`** — si el response ya se escribió antes de que el `AuthenticationEntryPoint`/`AccessDeniedHandler` corran, `getWriter()` podría lanzar `IllegalStateException`. Edge case de baja probabilidad en el flujo actual (deny-all sin filtros previos que escriban body), sin AC que lo exija hoy.
- **`V1__init.sql` asume privilegio para `CREATE EXTENSION "pgcrypto"`** — válido en dev (rol superusuario en docker-compose). Revisar si el PostgreSQL de producción es un servicio gestionado sin superusuario (algunos proveedores requieren pre-habilitar extensiones fuera de Flyway).
- **Perfil `prod` sin validación fail-fast de variables requeridas** — hoy, una variable de entorno faltante en `prod` produce un error genérico de placeholder no resuelto en vez de un mensaje claro. Endurecimiento futuro (ej. `@ConfigurationProperties` con `@Validated` o un `ApplicationRunner` de verificación de arranque).
- **Test de `swagger-ui.html` no verifica el header `Location` del redirect** — `AuthServiceApplicationTests.swaggerUiIsServedAndPublic` solo confirma 3xx, no el destino exacto. Bajo valor, springdoc controla el target.

## Deferred from: code review of story 1-2-registro-de-cuenta-con-email-de-verificacion (2026-07-02)

- **Canal lateral de timing entre "email ya existe" y "cuenta nueva"** — el camino de email duplicado responde casi de inmediato; el camino de cuenta nueva tarda lo que cuesta un hash BCrypt. Un atacante podría distinguir ambos casos por latencia pese a que el cuerpo de respuesta es idéntico (AC #2 solo garantiza el cuerpo, no el tiempo). Mitigación requiere un hash señuelo de costo equivalente en el camino duplicado — diseño propio, no trivial.
- **`sha256Hex`/comparación de hash de token no es de tiempo constante** — no se ejecuta código de búsqueda por hash todavía (`VerificationTokenRepository` solo tiene `save` en esta historia). Revisar cuando la Story 1.3 implemente el lookup de verificación.
- **`AccountStatus.valueOf(entity.getStatus())` sin protección contra un valor corrupto/desconocido en la fila** — hoy inalcanzable (solo se escribe `PENDING_VERIFICATION`). Revisar cuando existan más transiciones de estado (Story 3.2, bloqueo por fuerza bruta).
- **`LoggingEmailSender` no sanea el token/email antes de loggear** — riesgo teórico de log injection vía caracteres de control. Es un adapter de desarrollo temporal (ver pregunta abierta 1 del PRD sobre el proveedor SMTP real); se reemplaza cuando llegue el adapter de producción.
