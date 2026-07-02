---
title: "PRD: Auth-Service"
status: draft
created: 2026-07-01
updated: 2026-07-01
---

# PRD: Auth-Service

## 0. Propósito del Documento

Este PRD define los requisitos del microservicio **Auth-Service** para su implementación. Está dirigido al arquitecto (workflow `bmad-architecture`), al desglose de épicas/historias (`bmad-create-epics-and-stories`) y al desarrollador. Se construye sobre el [Product Brief](../../briefs/brief-auth-service-2026-07-01/brief.md). El vocabulario está anclado al Glosario (§3); los FRs están numerados globalmente y las suposiciones marcadas con `[ASSUMPTION]` e indexadas en §10. Las decisiones técnicas (stack, librerías, TTLs) viven en `addendum.md`, no aquí.

## 1. Visión

Auth-Service es el proveedor de identidad único de un ecosistema de microservicios: un servicio API-first que registra usuarios, verifica su identidad, emite credenciales portables (Access Tokens) y permite a cualquier otro servicio autorizar peticiones sin hablar con una base de datos de usuarios.

Para el usuario final significa registrarse en segundos —con email o con un clic vía Google/GitHub—, no perder nunca su cuenta (recuperación autoservicio) y que sus credenciales estén protegidas con estándares actuales. Para el desarrollador del ecosistema significa que la autenticación deja de reimplementarse: integrar un servicio nuevo se reduce a validar la firma de un token.

## 2. Usuario Objetivo

### 2.1 Jobs To Be Done

- **Usuario final:** "Quiero entrar a la aplicación de forma rápida y segura, sin crear otra contraseña si ya tengo Google/GitHub, y recuperar mi acceso yo mismo si la olvido."
- **Servicio consumidor (desarrollador):** "Quiero autorizar peticiones validando un token localmente, sin llamar a otro servicio en cada request."
- **Administrador:** "Quiero ver y gestionar las cuentas del sistema sin tocar la base de datos, y que quede rastro de lo que hice."
- **Operador/SRE:** "Quiero saber si el servicio está sano, ver métricas y trazas cuando algo falla, y que un proveedor externo caído no tumbe todo el sistema."
- **Builder (portafolio):** "Quiero un servicio de identidad que demuestre prácticas de arquitectura y operación de nivel empresarial."

### 2.2 No-Usuarios (v1)

- Organizaciones/equipos con multi-tenancy.
- Usuarios de SSO empresarial (SAML/LDAP).
- Aplicaciones que requieran MFA obligatorio.

### 2.3 User Journeys Clave

- **UJ-1. Registro clásico.** Un usuario nuevo se registra con email y contraseña desde una app consumidora; recibe un email de verificación, hace clic en el enlace y su Cuenta queda activa; inicia sesión y recibe su par de tokens. **Edge case:** si el enlace expira, puede solicitar reenvío.
- **UJ-2. Login con un clic.** Un usuario elige "Continuar con Google"; autoriza en Google y regresa autenticado con su par de tokens, sin haber creado contraseña. Si su email ya existía como Cuenta local, la identidad federada se vincula a esa Cuenta en lugar de duplicarla.
- **UJ-3. Sesión prolongada segura.** Una app consumidora usa el Access Token hasta que expira; entonces canjea el Refresh Token por un par nuevo sin intervención del usuario. Si un Refresh Token ya rotado se reutiliza (posible robo), toda la familia de tokens se revoca y el usuario debe reautenticarse.
- **UJ-4. Contraseña olvidada.** Un usuario solicita recuperación; recibe un enlace temporal por email, define una nueva contraseña y todas sus sesiones previas quedan invalidadas.
- **UJ-5. Ataque de fuerza bruta contenido.** Un atacante prueba contraseñas contra una Cuenta; tras el umbral de intentos fallidos la Cuenta queda bloqueada temporalmente y el titular es notificado por email. El atacante no obtiene señal de si la contraseña era correcta.
- **UJ-6. Operación administrativa.** Un Administrador lista las Cuentas, consulta el detalle de una y la desactiva; el usuario afectado no puede volver a autenticarse; la acción queda registrada en el Registro de Auditoría.
- **UJ-7. Diagnóstico bajo incidente.** Un Operador ve una alerta de error elevado; consulta las métricas y el estado de salud del servicio, sigue una traza para identificar si el fallo viene de un proveedor externo (Google/SMTP) o del propio servicio, y confirma si el circuito de resiliencia correspondiente ya está abierto conteniendo el impacto.
- **UJ-8. Reacción a eventos sin polling.** Un Servicio Consumidor se suscribe a los Eventos de Dominio de Auth-Service; cuando una Cuenta se bloquea o desactiva, el consumidor reacciona (por ejemplo, cerrando sesiones locales) sin necesidad de sondear periódicamente el estado de la Cuenta.

## 3. Glosario

- **Cuenta** — registro de un usuario en el sistema; identificada por email único. Estados: `PENDIENTE_VERIFICACION`, `ACTIVA`, `BLOQUEADA` (temporal, por intentos fallidos), `DESACTIVADA` (por Administrador). Una Cuenta tiene 1..n Roles y 0..n Identidades Federadas.
- **Rol** — nivel de autorización de una Cuenta: `USER` o `ADMIN`.
- **Access Token** — JWT firmado de corta duración que porta identidad y Roles; validable sin estado por servicios consumidores.
- **Refresh Token** — credencial opaca de larga duración, de un solo uso, canjeable por un nuevo par de tokens. Pertenece a una Familia de Tokens.
- **Familia de Tokens** — cadena de Refresh Tokens generada por rotación a partir de un login; se revoca completa ante reutilización de un miembro ya rotado.
- **Identidad Federada** — vínculo entre una Cuenta y un proveedor OAuth2 externo (Google, GitHub).
- **Token de Verificación** — token temporal de un solo uso enviado por email para confirmar la titularidad del email (verificación de Cuenta o recuperación de contraseña).
- **Servicio Consumidor** — cualquier microservicio del ecosistema que valida Access Tokens para autorizar peticiones, o se suscribe a sus Eventos de Dominio.
- **Administrador** — usuario con Rol `ADMIN`.
- **Evento de Dominio** — hecho inmutable publicado cuando cambia el estado de una Cuenta (creación, verificación, bloqueo, desactivación, revocación de una Familia de Tokens), consumible por Servicios Consumidores sin polling.
- **Registro de Auditoría** — bitácora append-only de toda acción administrativa (quién, qué, sobre qué Cuenta, cuándo, resultado); no editable ni eliminable.
- **Operador** — persona responsable de mantener el servicio saludable en producción (SRE/on-call); consume observabilidad, no la API de negocio.

## 4. Features

### 4.1 Registro y Verificación de Email

**Description:** Un visitante crea una Cuenta con email y contraseña. La Cuenta nace `PENDIENTE_VERIFICACION`; el sistema envía un Token de Verificación por email y la Cuenta pasa a `ACTIVA` al confirmarse. Realiza UJ-1.

#### FR-1: Registro con credenciales

Un visitante puede crear una Cuenta proporcionando email y contraseña que cumplan la política de validación.

**Consecuencias (testables):**
- Email con formato inválido o ya registrado → rechazo con error específico sin revelar si el email existe [ASSUMPTION: respuesta genérica "si el email es válido recibirás un correo" para no filtrar existencia de cuentas].
- Contraseña que no cumple la política (mínimo 8 caracteres, al menos una mayúscula, una minúscula, un dígito) → rechazo con detalle de la regla incumplida. [ASSUMPTION: política mínima estándar]
- La contraseña se almacena únicamente como hash criptográfico; nunca en claro ni recuperable.
- Registro exitoso → Cuenta en estado `PENDIENTE_VERIFICACION` con Rol `USER`.

#### FR-2: Verificación de email

El titular puede activar su Cuenta consumiendo el Token de Verificación recibido por email.

**Consecuencias (testables):**
- Token válido y vigente → Cuenta pasa a `ACTIVA`; el token queda consumido y no es reutilizable.
- Token expirado o inexistente → error; la Cuenta permanece `PENDIENTE_VERIFICACION`.
- El titular puede solicitar reenvío del Token de Verificación; el token anterior queda invalidado.
- Una Cuenta `PENDIENTE_VERIFICACION` no puede iniciar sesión (ver FR-3).

### 4.2 Autenticación con Credenciales y Ciclo de Tokens

**Description:** Una Cuenta `ACTIVA` inicia sesión con email y contraseña y recibe un par Access Token + Refresh Token. El Access Token es de corta duración; el Refresh Token rota en cada uso. Realiza UJ-3.

#### FR-3: Login con credenciales

El titular de una Cuenta `ACTIVA` puede iniciar sesión con email y contraseña, recibiendo un Access Token y un Refresh Token.

**Consecuencias (testables):**
- Credenciales válidas + Cuenta `ACTIVA` → par de tokens emitido; el Access Token porta identidad y Roles.
- Credenciales inválidas → error de autenticación genérico (mismo mensaje para email inexistente y contraseña errónea) y registro del intento fallido (ver FR-9).
- Cuenta `PENDIENTE_VERIFICACION`, `BLOQUEADA` o `DESACTIVADA` → autenticación rechazada con motivo distinguible para el titular pero sin filtrar información a terceros. [ASSUMPTION]

#### FR-4: Renovación con rotación de Refresh Token

Un cliente puede canjear un Refresh Token vigente por un nuevo par Access Token + Refresh Token.

**Consecuencias (testables):**
- Canje exitoso → el Refresh Token usado queda rotado (inutilizable) y se emite uno nuevo de la misma Familia de Tokens.
- Reutilización de un Refresh Token ya rotado → revocación inmediata de toda la Familia de Tokens; los canjes posteriores de cualquier miembro fallan.
- Refresh Token expirado o revocado → error; se requiere login completo.

#### FR-5: Logout

El titular puede cerrar sesión invalidando su Refresh Token (y con ello su Familia de Tokens).

**Consecuencias (testables):**
- Tras logout, el canje del Refresh Token falla.
- El Access Token vigente expira naturalmente por su TTL corto [NON-GOAL for MVP: lista de revocación de Access Tokens].

### 4.3 Login Social (OAuth2)

**Description:** Un usuario se autentica con Google o GitHub. Si el email federado coincide con una Cuenta existente, la Identidad Federada se vincula a esa Cuenta; si no, se crea una Cuenta `ACTIVA` (el proveedor ya verificó el email). Realiza UJ-2.

#### FR-6: Autenticación con Google y GitHub

Un visitante puede autenticarse mediante Google o GitHub y recibir el mismo par de tokens que en el login con credenciales.

**Consecuencias (testables):**
- Primer login federado sin Cuenta previa → se crea Cuenta `ACTIVA` con Rol `USER` e Identidad Federada asociada; sin contraseña local.
- Login federado con email ya registrado → la Identidad Federada se vincula a la Cuenta existente; logins posteriores por cualquiera de las dos vías acceden a la misma Cuenta.
- Fallo o cancelación en el proveedor → el flujo termina en error controlado sin crear estado parcial.
- Una Cuenta creada por vía federada puede definir contraseña local únicamente mediante el flujo de recuperación (FR-7). [ASSUMPTION]

### 4.4 Recuperación de Contraseña

**Description:** El titular restablece su contraseña de forma autoservicio mediante un enlace temporal enviado a su email. Realiza UJ-4.

#### FR-7: Solicitud y restablecimiento

El titular puede solicitar un Token de Verificación de recuperación y usarlo para definir una nueva contraseña.

**Consecuencias (testables):**
- La solicitud responde igual exista o no la Cuenta (no filtra existencia).
- Token válido → nueva contraseña aplicada (misma política de FR-1); token consumido.
- Restablecimiento exitoso → todas las Familias de Tokens de la Cuenta quedan revocadas (cierra sesiones en otros dispositivos).
- Token expirado o ya consumido → error; la contraseña anterior sigue vigente.

### 4.5 Protección de Cuenta

**Description:** El sistema contiene ataques de fuerza bruta bloqueando temporalmente Cuentas con intentos fallidos repetidos. Realiza UJ-5.

#### FR-8: Registro de intentos fallidos

El sistema registra cada intento de login fallido por Cuenta.

**Consecuencias (testables):**
- Cada fallo de credenciales incrementa el contador de la Cuenta objetivo.
- Un login exitoso reinicia el contador.

#### FR-9: Bloqueo temporal automático

El sistema bloquea una Cuenta al alcanzar el umbral de intentos fallidos.

**Consecuencias (testables):**
- Al llegar a 5 intentos fallidos dentro de la ventana, la Cuenta pasa a `BLOQUEADA` por 15 minutos [ASSUMPTION: umbral y duración configurables].
- Durante el bloqueo, incluso la contraseña correcta es rechazada con el mismo error genérico.
- Al expirar el bloqueo la Cuenta vuelve a `ACTIVA` automáticamente.
- El titular es notificado por email al producirse el bloqueo.

### 4.6 Perfil y Administración

**Description:** Toda Cuenta autenticada consulta su propio perfil; los Administradores gestionan Cuentas. Realiza UJ-6.

#### FR-10: Perfil propio

Una Cuenta autenticada puede consultar sus propios datos (email, Roles, estado, Identidades Federadas, fechas).

**Consecuencias (testables):**
- Petición con Access Token válido → datos de la Cuenta del token, nunca de otra.
- Sin token o token inválido/expirado → rechazo 401.

#### FR-11: Gestión administrativa de Cuentas

Un Administrador puede listar Cuentas, consultar su detalle, desactivarlas/reactivarlas y modificar sus Roles.

**Consecuencias (testables):**
- Peticiones con Rol `USER` → rechazo 403.
- Desactivación → la Cuenta pasa a `DESACTIVADA`, sus Familias de Tokens se revocan y no puede autenticarse por ninguna vía.
- Un Administrador no puede desactivarse ni quitarse el Rol `ADMIN` a sí mismo. [ASSUMPTION]
- Listado paginado. [ASSUMPTION]

#### FR-12: Aprovisionamiento del primer Administrador

El sistema garantiza la existencia de una Cuenta `ADMIN` inicial sin intervención manual en base de datos.

**Consecuencias (testables):**
- En el primer arranque, si no existe ningún `ADMIN`, se crea uno a partir de configuración (email/contraseña por variables de entorno). [ASSUMPTION]

#### FR-13: Auditoría de acciones administrativas

Toda acción ejecutada por un Administrador sobre una Cuenta queda registrada en el Registro de Auditoría, y un Administrador puede consultarlo.

**Consecuencias (testables):**
- Cada desactivación, reactivación o cambio de Rol ejecutado vía FR-11 crea una entrada de auditoría con actor, acción, Cuenta afectada, resultado y momento exacto, en la misma operación que el cambio (no puede quedar el cambio sin su registro).
- El Registro de Auditoría es append-only: ninguna vía de la API permite editar ni eliminar una entrada existente.
- Un Administrador puede consultar el Registro de Auditoría, filtrado por Cuenta afectada o por rango de fechas. [ASSUMPTION: filtros mínimos]

### 4.7 Integración por Eventos de Dominio

**Description:** Auth-Service publica Eventos de Dominio ante cambios relevantes de una Cuenta, para que los Servicios Consumidores reaccionen sin sondear el estado periódicamente. Realiza UJ-8.

#### FR-14: Publicación de eventos de cambio de estado

El sistema publica un Evento de Dominio cada vez que ocurre un cambio de estado significativo de una Cuenta (registro, verificación, bloqueo, desactivación, revocación de una Familia de Tokens).

**Consecuencias (testables):**
- Todo cambio de estado listado se refleja en un evento publicado, incluso si el suscriptor no está disponible en el momento del cambio (el evento no se pierde silenciosamente).
- Un evento nunca se publica si la operación de negocio que lo originó terminó revertida (consistencia entre el cambio y el evento que lo anuncia).
- El formato del evento es estable y versionado; un Servicio Consumidor existente no se rompe por un cambio aditivo (nuevo campo) en el payload.

**Out of Scope:**
- Garantía de entrega exactly-once end-to-end al consumidor final — se garantiza at-least-once; los consumidores deben tolerar duplicados (idempotencia del lado del consumidor).

## 5. Requisitos No Funcionales Transversales (NFR)

*Cada NFR aplica a través de features, no a una sola. Los NFR-1..10 formalizan calidad ya implícita en los FRs anteriores; NFR-11..16 son las capacidades de nivel operacional/empresarial incorporadas en esta revisión.*

- **NFR-1** — Seguridad de credenciales: contraseñas solo como hash; tokens de un solo uso y Refresh Tokens solo hasheados en almacenamiento; prohibido registrar secretos en logs. Aplica a FR-1, FR-2, FR-4, FR-7.
- **NFR-2** — Anti-enumeración: toda respuesta que pudiera revelar la existencia de una Cuenta responde de forma indistinguible exista o no. Aplica a FR-1, FR-3, FR-7.
- **NFR-3** — Rendimiento: p95 de login y renovación de token < 500 ms en entorno de referencia, sin degradar el costo del hashing de contraseñas. Aplica a FR-3, FR-4.
- **NFR-4** — Calidad de pruebas: cobertura ≥ 80% en la lógica de autenticación/autorización; suite de pruebas negativas de autorización al 100%. Aplica a todos los FR.
- **NFR-5** — Seguridad por defecto: toda ruta nueva nace protegida; lo público se declara explícitamente. Aplica a todos los FR.
- **NFR-6** — Integridad de esquema: la estructura de datos evoluciona solo mediante migraciones versionadas, nunca por generación automática en tiempo de ejecución. Aplica a todos los FR.
- **NFR-7** — Configuración por entorno: ningún parámetro de comportamiento (umbrales, TTLs, credenciales de terceros) queda hardcodeado. Aplica a FR-6, FR-9, FR-12.
- **NFR-8** — Arranque reproducible: el entorno completo de desarrollo se levanta con un solo comando. Aplica a todos los FR.
- **NFR-9** — Aislamiento de fallos de notificación: un proveedor de email caído no revierte ni bloquea la operación de negocio que originó el envío. Aplica a FR-2, FR-7, FR-9.
- **NFR-10** — Documentación de API: el contrato de la API es explorable y suficiente para que un Servicio Consumidor se integre en menos de 30 minutos. Aplica a todos los FR.
- **NFR-11** — Observabilidad operacional: el servicio expone su estado de salud (listo/vivo, apto para orquestador), métricas de uso y desempeño, y trazas correlacionables por petición — sin necesidad de leer logs crudos para saber si está sano.
- **NFR-12** — Resiliencia ante proveedores externos: la caída o lentitud de Google, GitHub o el proveedor de email no debe colgar peticiones ni degradar el servicio más allá de la funcionalidad que depende directamente de ese proveedor.
- **NFR-13** — Versionado sin ruptura: un cambio incompatible en un endpoint ya publicado no rompe a un Servicio Consumidor existente; convive una versión nueva en paralelo.
- **NFR-14** — Rotación de secretos sin downtime: el secreto de firma de tokens puede rotarse sin invalidar tokens emitidos con el secreto anterior durante una ventana de transición, y sin reiniciar el servicio con pérdida de disponibilidad.
- **NFR-15** — Auditoría append-only: ver FR-13; se trata también como NFR porque es una garantía transversal de integridad, no solo una feature consultable.
- **NFR-16** — Calidad como gate automatizado: ninguna violación de la separación de capas del servicio, caída de cobertura por debajo del umbral, o vulnerabilidad crítica conocida en una dependencia llega a la rama principal sin ser bloqueada por el proceso de integración.

## 6. Non-Goals (Explícitos)

- No es un proveedor de identidad multi-tenant ni un producto SaaS.
- No implementa MFA/TOTP, passkeys/WebAuthn ni SSO empresarial (SAML/LDAP) en v1.
- No sirve UI de login: es API-first; los formularios los renderizan las apps consumidoras.
- No hace rate limiting global (responsabilidad del gateway); solo el lockout por Cuenta de FR-9.
- No gestiona permisos granulares más allá de los dos Roles (`USER`/`ADMIN`).
- No emite tokens para comunicación servicio-a-servicio (client credentials).
- No implementa un bus de eventos genérico de propósito general — solo publica los Eventos de Dominio listados en FR-14, no un mecanismo de mensajería a la carta para otros dominios.

## 7. Alcance MVP

### 7.1 Dentro

- FR-1 a FR-14 completos.
- Envío de emails transaccionales (verificación, recuperación, aviso de bloqueo).
- Persistencia en PostgreSQL con esquema versionado.
- Publicación de Eventos de Dominio vía outbox transaccional (FR-14).
- Registro de Auditoría append-only (FR-13).
- Observabilidad operacional: salud, métricas, trazas (NFR-11).
- Resiliencia ante proveedores externos (NFR-12).
- Gate de calidad automatizado en el pipeline de integración (NFR-16).
- Arranque local con Docker Compose (servicio + base de datos + broker de eventos).
- Documentación de la API (contrato de endpoints, versionada).

### 7.2 Fuera del MVP

- MFA/passkeys — v2; requiere diseño de enrolamiento propio.
- Endpoint JWKS/rotación de claves asimétricas de firma — v2; en MVP se resuelve con rotación de secreto simétrico (NFR-14).
- Panel de administración con UI — sin fecha; la gestión es por API.
- Contract testing formal (Pact) hacia Servicios Consumidores — se adopta cuando exista un segundo consumidor real.
- Elección de la herramienta concreta de secret store (Vault/AWS Secrets Manager/k8s Secrets) — el contrato de rotación (NFR-14) no depende de cuál se use; la herramienta se decide al desplegar.

## 8. Métricas de Éxito

**Primarias**
- **SM-1**: Flujos E2E verdes — registro→verificación→login→refresh→logout y ambos flujos OAuth2 pasan pruebas de integración automatizadas. Valida FR-1..FR-6.
- **SM-2**: 0 bypasses de autorización — suite de pruebas negativas (tokens inválidos, expirados, rol insuficiente, tokens rotados reutilizados) al 100%. Valida FR-4, FR-10, FR-11.
- **SM-3**: Lockout efectivo — 5 fallos consecutivos bloquean la Cuenta y el 6º intento con contraseña correcta es rechazado. Valida FR-8, FR-9.

**Secundarias**
- **SM-4**: p95 de login y validación de refresh < 500 ms en entorno local con datos de prueba. [ASSUMPTION]
- **SM-5**: Cobertura de pruebas ≥ 80% en la lógica de autenticación/autorización.
- **SM-6**: Integración de un Servicio Consumidor de ejemplo en < 30 min siguiendo la documentación.

**Counter-metrics (no optimizar)**
- **SM-C1**: Velocidad de respuesta de login — no optimizar a costa de reducir el costo del hashing de contraseñas por debajo del estándar. Contrapesa SM-4.
- **SM-C2**: Fricción de registro — no eliminar la verificación de email para mejorar conversión. Contrapesa SM-1.
- **SM-C3**: Cobertura de trazas/métricas — no instrumentar a costa de loggear datos sensibles (contraseñas, tokens) en spans o labels. Contrapesa SM-7.

**Nuevas (nivel enterprise)**
- **SM-7**: Tiempo medio de diagnóstico — ante una caída simulada de un proveedor externo, un Operador identifica la causa raíz usando solo métricas/trazas/health en < 5 min. Valida NFR-11, NFR-12.
- **SM-8**: Cero eventos perdidos — en una prueba de caída del broker durante una ráfaga de mutaciones, el 100% de los eventos aparece en `outbox_event` y se publica al restablecerse la conexión. Valida FR-14.
- **SM-9**: Build bloqueado ante violación — un commit que rompe deliberadamente la regla de dependencias (domain→infrastructure) o baja la cobertura del umbral hace fallar el pipeline. Valida NFR-16.

## 9. Preguntas Abiertas

1. ¿Qué proveedor SMTP/email se usará en producción (y en dev: Mailhog/consola)? — afecta FR-2, FR-7, FR-9.
2. ¿Los Servicios Consumidores validarán el JWT con clave compartida (simétrica) o pública (asimétrica)? Recomendación diferida al arquitecto — afecta contrato con consumidores. Parcialmente mitigado por NFR-14 (rotación de secreto simétrico) mientras se decide.
3. ¿Se necesita endpoint de introspección de tokens para consumidores que no quieran validar localmente?
4. ¿Dominio/URL base para los enlaces de email en cada entorno?
5. ¿Qué herramienta de secret store (Vault/AWS Secrets Manager/k8s Secrets) y qué topología de broker (Kafka gestionado/self-hosted/Redpanda Cloud) se usarán en producción? No bloquea el desarrollo — NFR-14 y FR-14 fijan el contrato, no la herramienta.
6. ¿Qué formato de esquema de eventos se usa para el payload publicado (JSON simple, Avro, CloudEvents)? Afecta FR-14 y la facilidad de evolución del contrato.

## 10. Índice de Assumptions

- §4.1 FR-1 — Respuesta genérica en registro para no filtrar existencia de cuentas.
- §4.1 FR-1 — Política de contraseña: ≥8 caracteres, mayúscula, minúscula y dígito.
- §4.2 FR-3 — Motivo de rechazo distinguible para el titular sin filtrar a terceros.
- §4.3 FR-6 — Cuenta federada define contraseña local solo vía recuperación.
- §4.5 FR-9 — Umbral 5 intentos / bloqueo 15 min, configurables.
- §4.6 FR-11 — Auto-protección de ADMIN (no auto-desactivación); listado paginado.
- §4.6 FR-12 — Admin inicial por variables de entorno en primer arranque.
- §4.6 FR-13 — Filtros mínimos de consulta del Registro de Auditoría (por Cuenta o rango de fechas).
- §8 SM-4 — Presupuesto p95 500 ms en local.
- Brief — Proyecto de portafolio sin requisitos de compliance formales; API-first sin UI propia.
