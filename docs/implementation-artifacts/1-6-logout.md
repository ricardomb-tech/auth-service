---
baseline_commit: 704433e6343a0b75344cee030bccb3988c781ff4
---

# Story 1.6: Logout

Status: done

## Story

As a titular con sesión activa,
I want cerrar sesión,
so that mis credenciales de renovación queden inutilizables. (FR-5)

## Acceptance Criteria

1. Con un Refresh Token vigente, `POST /auth/logout` invoca `LogoutUseCase`, que revoca de forma atómica **su** Familia de Tokens (la del token presentado — no todas las Familias de la Cuenta, ver Dev Notes) vía `RefreshTokenRepository.revokeFamily` (AD-4, AD-13), y responde `204 No Content` sin cuerpo.
2. Un canje posterior de ese Refresh Token, o de cualquier otro miembro vivo de la misma Familia, vía `POST /auth/refresh` responde el mismo 401 genérico que cualquier otro Refresh Token inválido (`InvalidRefreshTokenException`, ya existente desde la Story 1.5).
3. El Access Token emitido junto con ese Refresh Token sigue siendo válido para peticiones autenticadas hasta su expiración natural — logout no lo invalida (Non-Goal explícito del PRD). `JwtAuthenticationFilter` no cambia en esta historia: nunca consulta `refresh_tokens`, por lo que este AC ya está garantizado por la arquitectura actual sin código nuevo (ver Dev Notes).
4. Con un Refresh Token no reconocido (hash sin fila), ya revocado, ya usado (rotado) o expirado, `POST /auth/logout` responde igualmente `204 No Content` — logout es idempotente y no distingue el motivo ni revela si el token era válido (mismo principio anti-enumeración que AD-8, aplicado aquí aunque no haya Cuenta en juego en la respuesta).

## Tasks / Subtasks

- [x] Task 1: `LogoutCommand` (`application/usecase`) (AC: #1, #4)
  - [x] `record LogoutCommand(String rawRefreshToken)` — mismo patrón exacto que `RefreshCommand` (Story 1.5).

- [x] Task 2: `LogoutUseCase` (`application/usecase`) (AD-4, AD-13) (AC: #1, #2, #3, #4)
  - [x] Clase `@Service`, `@Transactional` **simple** (no `noRollbackFor`, a diferencia de `RefreshTokenUseCase`): este caso de uso nunca lanza excepción — no hay AC que distinga en la respuesta "token inválido" de "token válido", ambos casos terminan en `204`. No hay rollback que evitar.
  - [x] Constructor-injected con **solo** `RefreshTokenRepository` y `Clock` — **no** inyectar `AccountRepository` ni `TokenIssuer`: a diferencia de `RefreshTokenUseCase`, logout no emite tokens nuevos ni necesita leer el estado de la Cuenta, solo revocar una Familia existente. Copiar la lista de dependencias de `RefreshTokenUseCase` sería sobre-ingeniería no pedida por ningún AC.
  - [x] Método `void logout(LogoutCommand command)` en este orden exacto:
    1. `String tokenHash = RefreshToken.hashRawToken(command.rawRefreshToken())`.
    2. `refreshTokenRepository.findByTokenHash(tokenHash)` — si vacío, `return` sin hacer nada más (AC #4, token no reconocido).
    3. Si se encontró: `refreshTokenRepository.revokeFamily(token.familyId(), Instant.now(clock))` — **sin** comprobar `usedAt()`/`revokedAt()`/`expiresAt()` del propio token primero. Revocar la familia es seguro e idempotente sin importar el estado del token presentado (`revokeFamily` ya es un `UPDATE` condicional sobre `revoked_at IS NULL` fila por fila, Story 1.5): un token ya usado/revocado/expirado sigue identificando una Familia válida que el titular quiere matar, y no hay ningún AC que pida rechazar el logout en esos casos — al contrario, AC #4 pide `204` igual.
  - [x] **No** loguear a nivel `WARN` como hace `RefreshTokenUseCase.revokeFamilyOnReuse` — esto no es una señal de robo, es la acción intencional del propio titular. Si se quiere logging informativo, `DEBUG`/`INFO` con `accountId`+`familyId` (nunca el token), pero no es un requisito de ningún AC de esta historia.
  - [x] Tests unitarios con mocks: token no reconocido (`findByTokenHash` vacío) → `revokeFamily` nunca invocado, el método retorna normalmente sin lanzar; token válido y sin usar → `revokeFamily` invocado exactamente una vez con el `familyId` correcto; token ya usado (`usedAt` no nulo) → `revokeFamily` invocado igual una vez (mismo `familyId`); token ya revocado (`revokedAt` no nulo) → `revokeFamily` invocado igual (es un no-op a nivel de fila, pero el caso de uso no necesita saberlo de antemano); token expirado → `revokeFamily` invocado igual. En ningún caso el método lanza una excepción.

- [x] Task 3: DTO + Controller (`infrastructure`) (AC: #1, #4)
  - [x] `infrastructure/controller/dto/LogoutRequest(String refreshToken)` — `@NotBlank`, mismo patrón exacto que `RefreshRequest` (Story 1.5).
  - [x] `AuthController` (UPDATE): nuevo endpoint `POST /auth/logout` invoca `logoutUseCase.logout(new LogoutCommand(request.refreshToken()))` y devuelve `ResponseEntity.noContent().build()` (`204`, sin cuerpo) — a diferencia de `/login` y `/refresh`, no hay tokens que devolver. No hay try/catch: el caso de uso nunca lanza.
  - [x] `AuthControllerTest` (UPDATE): añadir `@MockitoBean LogoutUseCase`. Tests: logout con `refreshToken` no vacío → `204` sin cuerpo, `logoutUseCase.logout(...)` invocado con el comando correcto; logout con `refreshToken` en blanco → `400` (validación `@NotBlank`, `logoutUseCase` nunca invocado) — mismo patrón que `refreshWithBlankTokenReturns400`.

- [x] Task 4: Test de integración (AC: #1, #2, #4)
  - [x] `AuthLogoutIntegrationTest` — `@SpringBootTest` + Testcontainers + `@Transactional`, mismo patrón exacto que `AuthRefreshIntegrationTest` (Story 1.5): Refresh Token de partida vía login real (`POST /auth/login`).
  - [x] Casos: logout de un Refresh Token vigente → `204`, la fila del token queda con `revoked_at` no nulo **verificado leyendo la fila real de la base de datos** tras la petición; canje posterior de ese mismo token vía `POST /auth/refresh` → `401` `application/problem+json` (AC #2); logout de un token no reconocido (string aleatorio) → `204` igual, sin error (AC #4); doble logout del mismo token (logout dos veces seguidas) → `204` ambas veces, sin error (AC #4, `revokeFamily` es un no-op idempotente la segunda vez); suite completa (`./mvnw -B verify`) sin regresión en Stories 1.1-1.5.
  - [x] **No** se necesita un test de integración end-to-end para AC #3 (Access Token sigue válido tras logout) a través de un endpoint protegido real — hoy no existe ninguno montado en la aplicación (`/api/v1/users/me` es la Story 1.7, todavía `backlog`; añadir uno aquí sería scope creep no pedido). AC #3 está garantizado por construcción: `JwtAuthenticationFilter` (Story 1.4) nunca consulta `refresh_tokens`, valida el JWT únicamente por firma/claims/`exp` — ya cubierto por la suite existente de `JwtAuthenticationFilterTest`, que no depende en ningún punto del estado de ningún Refresh Token. No añadir un endpoint protegido de prueba solo para esta historia.

### Review Findings

- [x] [Review][Defer] Carrera entre `/auth/refresh` concurrente y `/auth/logout` puede dejar un token vivo tras el 204 — `LogoutUseCase.revokeFamily(familyId, now)` es un único `UPDATE` masivo condicional (`WHERE family_id = ? AND revoked_at IS NULL`), pero no coordina con una inserción concurrente. Si otra petición `/auth/refresh` para un token hermano de la misma Familia está en pleno vuelo (ya pasó su propia lectura/validación, a punto de `tokenIssuer.issue(...)` → `save(...)`) justo cuando corre `revokeFamily`, el `INSERT` del nuevo token puede completarse **después** del `UPDATE` de logout — ese nuevo token queda vivo (`revoked_at IS NULL`) pese al 204 ya devuelto al cliente, violando la promesa central de FR-5 ("mis credenciales de renovación queden inutilizables"). No es un bug introducido por esta historia: el mismo mecanismo de `revokeFamily` de la Story 1.5 (camino de detección de reuso) tiene la misma ventana de carrera frente a un `refresh` concurrente de un hermano — esta historia solo agrega un segundo punto de entrada que la ejercita. Arreglarlo bien requeriría coordinación adicional (ej. un flag de "familia revocada" chequeado atómicamente en el momento de emitir, o un lock explícito sobre la familia) — no es un fix de una línea. **Deferred, razón:** riesgo compartido con la Story 1.5 (el mismo mecanismo `revokeFamily` ya tenía esta ventana), sin datos de uso real que indiquen que ocurre en producción — no justifica un rediseño de concurrencia ahora. [src/main/java/com/auth_service/auth/application/usecase/LogoutUseCase.java:33-38]
- [x] [Review][Patch] La revocación de una Familia completa (múltiples tokens vivos) nunca se verifica end-to-end — todos los tests (unitarios e integración) solo cubren el escenario de un único token en su Familia; el sub-clause de AC #2 ("o de cualquier otro miembro vivo de la misma Familia") queda sin cubrir. Agregar un test de integración con 2 tokens vivos en la misma Familia (login → refresh una vez para generar el hermano → logout con uno de los dos → el otro también falla en `/auth/refresh`). Aplicado: `logoutRevokesTheWholeFamilyNotJustThePresentedToken`. [src/test/java/com/auth_service/auth/infrastructure/controller/AuthLogoutIntegrationTest.java]
- [x] [Review][Patch] Verificación de mock débil en `logoutReturns204WithNoBodyAndInvokesUseCase` — `verify(logoutUseCase).logout(any())` no confirma que el `LogoutCommand` realmente lleve el `refreshToken` enviado en el body; un bug que mapeara mal el campo pasaría el test igual. Aplicado: ahora verifica `logout(eq(new LogoutCommand("some-raw-refresh-token")))`. [src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java]
- [x] [Review][Defer] Canal lateral de timing en `/auth/logout` (camino "no encontrado" responde de inmediato; camino "encontrado" hace una escritura extra a la BD) — cuarta aparición del mismo patrón ya diferido en Stories 1.2/1.3/1.4; la propia entrada de la Story 1.4 en `deferred-work.md` ya señalaba escalarlo a una historia dedicada si volvía a aparecer. [src/main/java/com/auth_service/auth/application/usecase/LogoutUseCase.java]

**Descartados como ruido (verificados contra el código real, el Blind Hunter no tiene acceso al repo):**
- "`revokeFamily` pisa el `revokedAt` original en logouts repetidos" — falso: el `@Query` de `RefreshTokenJpaRepository.revokeFamily` ya incluye `WHERE r.revokedAt IS NULL` (Story 1.5), es un no-op en un segundo logout.
- "El comentario del controller sobre 'nunca lanza' es una promesa no garantizada" — ya hay una red de seguridad genérica: `GlobalExceptionHandler.handleUnexpectedException` captura cualquier excepción no mapeada y devuelve 500 `problem+json` (desde la Story 1.2).
- "Sin cambios en `SecurityConfig`, no está claro si el endpoint es alcanzable" — falso: `/auth/**` es público desde la Story 1.1 (`SecurityConfig.PUBLIC_ENDPOINTS`).
- "Sin `@Size` en `LogoutRequest.refreshToken`" — coincide con `RefreshRequest` (mismo patrón exacto, tampoco tiene `@Size`), no es una regresión de esta historia.
- "El test de integración no cubre AC #3" — ya justificado explícitamente en Dev Notes: AC #3 no requiere código ni test nuevo, está garantizado por arquitectura.
- "El doble logout no verifica que `revokedAt` no cambie" — no hay bug que esa aserción pudiera atrapar, dado que `revokeFamily` ya es un no-op verificado en la fila.
- "Sin test de JSON malformado/campo faltante", "el 401 de refresh solo verifica content-type, no el detalle exacto", "concatenación de strings en los tests de integración" — coinciden con el patrón ya establecido en el resto de la suite (`AuthRefreshIntegrationTest`, etc.), no son gaps nuevos de esta historia.
- "Sin logging de auditoría en `LogoutUseCase`" — decisión deliberada ya documentada en la Task 2 de la historia (logout no es una señal de robo).
- "Procedencia del bean `Clock` no visible en el diff" — falso positivo por falta de acceso al repo; el bean ya existe y se usa desde la Story 1.4.
- "`LogoutCommand` sin guarda contra `rawRefreshToken` nulo" — mismo patrón exacto que `RefreshCommand` (sin guarda tampoco), inalcanzable por la única vía real de entrada (`@NotBlank` en el DTO).

## Dev Notes

- **Continuidad con la Story 1.5 (done):** `RefreshTokenRepository.revokeFamily(UUID familyId, Instant revokedAt)` ya existe y es exactamente lo que esta historia necesita — un `UPDATE` masivo condicional (`WHERE family_id = ? AND revoked_at IS NULL`) que ya se reutiliza tal cual, sin ningún método de repositorio nuevo. Los propios Dev Notes de la Story 1.5 ya anticipaban esto: *"1.6 reutilizará el mismo `revokeFamily(familyId, revokedAt)` que esta historia introduce — no se necesita una revocación 'por Cuenta completa' en 1.5"*.
- **Decisión abierta — alcance de la revocación (Familia del token vs. todas las Familias de la Cuenta):** `ARCHITECTURE-SPINE.md#AD-4` dice textualmente *"Logout, restablecimiento de contraseña (FR-7) y desactivación (FR-11) revocan **todas** las familias de la Cuenta"*, agrupando logout junto a dos operaciones administrativas/de seguridad (reset de password, desactivación) que sí tienen sentido revocando todo. Pero tanto `epics.md#Story-1.6` como `PRD#FR-5` son explícitos y singulares: *"invalidando **su** Refresh Token (y con ello **su** Familia de Tokens)"* — sin mencionar otras Familias (otros dispositivos/sesiones). Esta historia implementa la lectura de `epics.md`/`FR-5` (revocar solo la Familia del token presentado) porque: (a) es el AC concreto y testeable, no una frase de arquitectura; (b) es el comportamiento esperado por UX estándar de "logout" (cerrar sesión en este dispositivo no debería desloguear los demás); (c) ya existe el método exacto para esto (`revokeFamily(familyId, ...)`) sin necesitar un método nuevo tipo "revocar todas las familias de una Cuenta". La lectura de AD-4 probablemente sobre-generaliza para las tres operaciones que lista; el reset de password (FR-7, Epic 3) y la desactivación administrativa (FR-11, Epic 4) sí necesitarán su propio método de revocación masiva por Cuenta cuando lleguen esas historias — no anticiparlo aquí. **Confirmado con el usuario:** solo la Familia del token presentado (2026-07-11).
- **`LogoutUseCase` no necesita `AccountRepository` ni `TokenIssuer`** — a diferencia de `LoginUseCase`/`RefreshTokenUseCase`, no emite tokens ni valida estado de Cuenta. Mantenerlo mínimo: solo `RefreshTokenRepository` + `Clock`.
- **Por qué no hay `noRollbackFor`:** `RefreshTokenUseCase` lo necesita porque lanza `InvalidRefreshTokenException` tras revocar (para no perder la revocación en el rollback). `LogoutUseCase` nunca lanza — no hay excepción de la que protegerse.
- **204 sin cuerpo, no 200 con `MessageResponse`:** a diferencia de `/register`/`/resend-verification` (que sí devuelven un mensaje genérico porque hay una respuesta HTTP con contenido esperado por el cliente), logout no tiene nada útil que comunicar — `204 No Content` es la convención REST estándar para esta operación y evita inventar un DTO de respuesta vacío.
- **AC #3 (Access Token sigue vigente) no requiere ningún cambio de código en esta historia** — es una propiedad ya garantizada por AD-3 (Access Token stateless, nunca se persiste ni se revoca individualmente) y por cómo ya funciona `JwtAuthenticationFilter` desde la Story 1.4. Está aquí como AC solo para dejar explícito el Non-Goal del PRD, no como trabajo pendiente.
- **Riesgos heredados, no de esta historia (no resolver aquí):** el mismo canal lateral de timing y el mismo FK sin `ON DELETE` explícito ya documentados en `deferred-work.md` (Stories 1.2-1.5) no aplican a este endpoint (no hay comparación de hash de tiempo variable ni nueva escritura de FK), así que no hay una entrada nueva que agregar por esta historia.

### Project Structure Notes

- No se crean paquetes nuevos — todo el trabajo cae en paquetes ya existentes desde la Story 1.5: `application/usecase`, `infrastructure/controller/dto`.
- No se crea ninguna migración Flyway nueva — no hay columnas ni tablas nuevas; se reutiliza `refresh_tokens` (V3, Story 1.4) y su índice `idx_refresh_tokens_family_id` (ya usado por `revokeFamily` desde la Story 1.5).
- `AuthController`, `AuthControllerTest` son UPDATE (no NEW) — ya existen desde la Story 1.2 y se extienden aquí.
- Nuevos: `LogoutCommand`, `LogoutUseCase`, `LogoutRequest`, y sus tests correspondientes (`LogoutUseCaseTest`, `AuthLogoutIntegrationTest`).

### References

- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-4] — Refresh Token opaco, hasheado, rotación con detección de reuso por familia; incluye la frase sobre logout discutida arriba en Dev Notes
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-13] — casos de uso como frontera transaccional
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-3] — Access Token stateless, nunca se persiste ni se revoca individualmente (fundamenta AC #3)
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-5] — "El titular puede cerrar sesión invalidando su Refresh Token (y con ello su Familia de Tokens)"
- [Source: docs/planning-artifacts/epics.md#Story-1.6] — historia de usuario y criterio de aceptación original (Given/When/Then)
- [Source: docs/implementation-artifacts/1-5-renovacion-con-rotacion-y-deteccion-de-reuso.md] — `RefreshTokenRepository.revokeFamily`, `RefreshToken.hashRawToken`, patrón de `RefreshCommand`/`RefreshRequest`/`AuthRefreshIntegrationTest` que esta historia reutiliza tal cual

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- `./mvnw -q -o test -Dtest=LogoutUseCaseTest` — 5/5 en verde (Task 2, mocks, sin Docker). Test creado primero y confirmado en rojo (compilación fallida por `LogoutUseCase` inexistente) antes de implementar la clase.
- `./mvnw -o test -Dtest=AuthControllerTest` — 9/9 en verde (Task 3, slice `@WebMvcTest`, incluye los 2 tests nuevos de `/auth/logout`).
- `./mvnw -o test -Dtest=AuthLogoutIntegrationTest` — 3/3 en verde contra PostgreSQL real vía Testcontainers (Docker Desktop disponible en esta sesión).
- `./mvnw -B -o verify` final (suite completa) — **144/144 tests en verde, 0 skipped** (Docker disponible). JaCoCo `check` sobre `domain/`+`application/` en verde. `ArchitectureRulesTest` (ArchUnit) 3/3 en verde, sin violaciones de capas. `BUILD SUCCESS`.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Las 4 AC están implementadas: logout de un Refresh Token vigente revoca su Familia completa vía `RefreshTokenRepository.revokeFamily` y responde `204` (AC #1); canje posterior de ese token o de cualquier miembro de la misma Familia vía `/auth/refresh` responde 401 genérico (AC #2, verificado en integración); el Access Token emitido junto con ese Refresh Token sigue autenticando hasta su expiración natural — sin cambios en `JwtAuthenticationFilter`, que nunca consulta `refresh_tokens` (AC #3, garantizado por construcción, ya cubierto por `JwtAuthenticationFilterTest`); un token no reconocido, ya usado, ya revocado o expirado responde igual `204` sin distinguir el motivo (AC #4).
- `LogoutUseCase` quedó con solo dos dependencias (`RefreshTokenRepository`, `Clock`) — no se copiaron `AccountRepository`/`TokenIssuer` de `RefreshTokenUseCase`, tal como pedía la historia, porque logout no emite tokens ni valida estado de Cuenta.
- Alcance de la revocación implementado según la decisión confirmada en la historia: solo la Familia del token presentado, no todas las Familias de la Cuenta (ver Dev Notes sobre la tensión con `AD-4`).
- `@Transactional` simple en `LogoutUseCase` (sin `noRollbackFor`) — el caso de uso nunca lanza, no hay rollback del que protegerse.
- No se tocó `JwtAuthenticationFilter`, `SecurityConfig` ni ninguna migración Flyway — `/auth/**` ya era público desde la Story 1.1, y `revokeFamily` ya existía desde la Story 1.5.

### File List

**Nuevos:**
- `src/main/java/com/auth_service/auth/application/usecase/LogoutCommand.java`
- `src/main/java/com/auth_service/auth/application/usecase/LogoutUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/LogoutRequest.java`
- `src/test/java/com/auth_service/auth/application/usecase/LogoutUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthLogoutIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/infrastructure/controller/AuthController.java` (nuevo endpoint `POST /auth/logout`)
- `src/test/java/com/auth_service/auth/infrastructure/controller/AuthControllerTest.java` (`LogoutUseCase` mockeado, tests de `/auth/logout`)

## Change Log

| Fecha | Cambio |
|---|---|
| 2026-07-11 | Creación de la Story 1.6 a partir de epics.md, PRD FR-5, ARCHITECTURE-SPINE.md y continuidad con la Story 1.5. |
| 2026-07-11 | Implementación de la Story 1.6 (Tasks 1-4): logout con revocación de la Familia del Refresh Token presentado. |
