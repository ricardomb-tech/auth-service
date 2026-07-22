---
baseline_commit: e4d842d6da04144d8a977ca45fec90ef32e5a0f9
---

# Story 4.3: Consulta del Registro de Auditoría

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Administrador,
I want consultar el historial de acciones administrativas filtrando por Cuenta afectada o por rango de fechas,
so that pueda investigar qué pasó con una Cuenta o quién ejecutó un cambio. (FR-13)

## Acceptance Criteria

1. Dado un Access Token con Rol `ADMIN`, cuando hago GET `/api/v1/admin/audit-log?accountId={id}`, entonces recibo las entradas de auditoría donde esa Cuenta fue el objetivo (`target_account_id`), ordenadas por `occurredAt` descendente.
2. Dado un Access Token con Rol `ADMIN`, cuando hago GET `/api/v1/admin/audit-log?from={fecha}&to={fecha}`, entonces recibo las entradas cuyo `occurredAt` cae dentro del rango `[from, to]` (inclusive), ordenadas por `occurredAt` descendente. Ambos filtros (`accountId` y `from`/`to`) son combinables entre sí y opcionales — sin ningún filtro, devuelve todas las entradas ordenadas igual.
3. Dado un Access Token con Rol `USER` (sin `ADMIN`), cuando llamo a `/api/v1/admin/audit-log`, entonces recibo 403 `application/problem+json` (mismo mecanismo `@PreAuthorize("hasRole('ADMIN')")` que el resto de `/api/v1/admin/**`).
4. Ninguna vía de la API permite editar ni eliminar una entrada existente de `audit_log` — **ya garantizado por la Story 4.2** (trigger `audit_log_append_only`/`audit_log_append_only_truncate` en `V5__audit_log.sql`, ver Dev Notes). Esta historia no añade ni modifica esa protección; solo la hereda.

## Tasks / Subtasks

- [x] Task 1: Índices para las consultas de esta historia (AC: #1, #2) — deferred desde Story 4.2
  - [x] Nueva migración `src/main/resources/db/migration/V6__audit_log_query_indexes.sql`:
    ```sql
    CREATE INDEX idx_audit_log_target_account_id ON audit_log (target_account_id);
    CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at);
    ```
    Story 4.2 dejó esto explícitamente diferido para esta historia (ver `docs/implementation-artifacts/deferred-work.md`, entrada "Sin índice en `audit_log`..."). Sin estos índices, `search()` (Task 2) hace un `seq scan` completo de la tabla en cuanto crezca — es carga real de esta historia, no opcional.

- [x] Task 2: `AuditLogRepository.search` + adapter (AC: #1, #2)
  - [x] Extender `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java` (Java puro, AD-1 — nada de `Pageable`/`Instant` de Spring, `Instant` de `java.time` sí es válido):
    ```java
    List<AuditLogEntry> search(AccountId targetAccountId, Instant from, Instant to);
    ```
    Los tres parámetros son individualmente nullable — cualquier combinación de filtros, incluida ninguna (devuelve todo).
  - [x] `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java` — añadir método con `@Query` JPQL (primera vez que este patrón aparece en el proyecto; los repos existentes usan solo derived-query-methods porque nunca necesitaron filtros opcionales combinables — no hay Specification/Criteria API existente que reutilizar, y añadir uno nuevo sería sobre-ingeniería para 3 filtros):
    ```java
    @Query("SELECT a FROM AuditLogEntity a WHERE "
            + "(:targetAccountId IS NULL OR a.targetAccountId = :targetAccountId) AND "
            + "(:from IS NULL OR a.occurredAt >= :from) AND "
            + "(:to IS NULL OR a.occurredAt <= :to) "
            + "ORDER BY a.occurredAt DESC")
    List<AuditLogEntity> search(@Param("targetAccountId") UUID targetAccountId,
                                 @Param("from") Instant from, @Param("to") Instant to);
    ```
  - [x] `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java` — implementar `search`, delegando al método de arriba y mapeando cada `AuditLogEntity` a `AuditLogEntry` (mismo mapeo inverso que ya existe en `save`, pero de entity→domain: `new AccountId(entity.getActorAccountId())`, `AdminAction.valueOf(entity.getAction())`, `AuditResult.valueOf(entity.getResult())`).

- [x] Task 3: `QueryAuditLogUseCase` (AC: #1, #2) — nuevo, `application/usecase/` (AD-13)
  - [x] `src/main/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCase.java`:
    ```java
    @Service
    public class QueryAuditLogUseCase {

        private final AuditLogRepository auditLogRepository;

        public QueryAuditLogUseCase(AuditLogRepository auditLogRepository) {
            this.auditLogRepository = auditLogRepository;
        }

        @Transactional(readOnly = true)
        public List<AuditLogEntry> search(String rawAccountId, String rawFrom, String rawTo) {
            AccountId targetAccountId = parseOptionalAccountId(rawAccountId);
            Instant from = parseOptionalInstant(rawFrom, "from");
            Instant to = parseOptionalInstant(rawTo, "to");
            if (from != null && to != null && from.isAfter(to)) {
                throw new DomainValidationException("from no puede ser posterior a to.");
            }
            return auditLogRepository.search(targetAccountId, from, to);
        }

        private AccountId parseOptionalAccountId(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return new AccountId(UUID.fromString(raw));
            } catch (IllegalArgumentException malformed) {
                throw new DomainValidationException("accountId no es un UUID válido.");
            }
        }

        private Instant parseOptionalInstant(String raw, String paramName) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException malformed) {
                throw new DomainValidationException(paramName + " no es una fecha ISO-8601 válida.");
            }
        }
    }
    ```
    **Por qué parsear `String` a mano en vez de tipar `@RequestParam` como `UUID`/`Instant` directamente en el controller:** mismo principio ya establecido en `ManageAccountUseCase.parseActorId`/`findTarget` (Story 4.2) — el parseo/validación de estos valores es una decisión de negocio explícita (mensaje de error consistente vía `DomainValidationException` → 400 `application/problem+json`), no un detalle incidental de deserialización HTTP. Tipar el `@RequestParam` directamente delegaría el error a Spring's `MethodArgumentTypeMismatchException` con un mensaje/contrato distinto y menos controlado.
  - [x] `Instant.parse` exige el formato completo `yyyy-MM-ddTHH:mm:ssZ` (ISO-8601 con offset/zona) — un cliente que mande solo `2026-07-01` (sin hora) recibe 400. Es una limitación conocida y aceptable: no hay AC que pida aceptar fecha-sin-hora, y añadir esa tolerancia sería un parser a medida sin pedido explícito.

- [x] Task 4: `AdminAuditLogController` + DTO (AC: #1, #2, #3)
  - [x] **Controller nuevo y separado de `AdminController`** — no añadir estos endpoints a `AdminController`: su `@RequestMapping` está anclado a `/api/v1/admin/accounts` (Story 4.2) y `/api/v1/admin/audit-log` es un recurso hermano, no un sub-recurso de cuentas. Mismo criterio de separación por recurso que ya existe entre `AuthController`/`UserController`/`AdminController`.
  - [x] `src/main/java/com/auth_service/auth/infrastructure/controller/dto/AuditLogEntryResponse.java`:
    ```java
    public record AuditLogEntryResponse(String id, String actorAccountId, String targetAccountId,
                                         String action, String result, Instant occurredAt) {
    }
    ```
  - [x] `src/main/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogController.java`:
    ```java
    @RestController
    @RequestMapping("/api/v1/admin/audit-log")
    @PreAuthorize("hasRole('ADMIN')")
    public class AdminAuditLogController {

        private final QueryAuditLogUseCase queryAuditLogUseCase;

        public AdminAuditLogController(QueryAuditLogUseCase queryAuditLogUseCase) {
            this.queryAuditLogUseCase = queryAuditLogUseCase;
        }

        @GetMapping
        public ResponseEntity<List<AuditLogEntryResponse>> search(@RequestParam(required = false) String accountId,
                                                                     @RequestParam(required = false) String from,
                                                                     @RequestParam(required = false) String to) {
            List<AuditLogEntryResponse> entries = queryAuditLogUseCase.search(accountId, from, to).stream()
                    .map(this::toResponse).toList();
            return ResponseEntity.ok(entries);
        }

        private AuditLogEntryResponse toResponse(AuditLogEntry entry) {
            return new AuditLogEntryResponse(entry.id().toString(), entry.actorAccountId().value().toString(),
                    entry.targetAccountId().value().toString(), entry.action().name(), entry.result().name(),
                    entry.occurredAt());
        }
    }
    ```
  - [x] No se necesita ningún cambio en `SecurityConfig`/`GlobalExceptionHandler`: `@PreAuthorize("hasRole('ADMIN')")` ya cubre 403 vía el `AccessDeniedException` handler añadido en Story 4.2, y `DomainValidationException` ya tiene handler → 400 desde Story 1.2. Si ambos no están ya cableados, es una regresión, no algo que esta historia deba re-implementar.

- [x] Task 5: Tests de dominio/aplicación — `QueryAuditLogUseCaseTest` (AC: #1, #2)
  - [x] Nuevo `src/test/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCaseTest.java`, mismo patrón mock-based que `ManageAccountUseCaseTest`:
    - `searchWithNoFiltersReturnsAllEntriesFromRepository`
    - `searchWithAccountIdFilterParsesAndDelegatesToRepository`
    - `searchWithMalformedAccountIdThrowsDomainValidationException`
    - `searchWithFromAndToParsesAndDelegatesToRepository`
    - `searchWithMalformedFromThrowsDomainValidationException`
    - `searchWithFromAfterToThrowsDomainValidationException`
    - `searchWithBlankOptionalParamsTreatsThemAsAbsent` — `""` para `accountId`/`from`/`to` no debe intentar parsear, debe tratarse como filtro ausente (verificar que `auditLogRepository.search` se llama con `null` en esa posición)

- [x] Task 6: Test de controller — `AdminAuditLogControllerTest` (AC: #1, #2, #3)
  - [x] Nuevo `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogControllerTest.java`, mismo patrón `@WebMvcTest` + JWT armado a mano que `AdminControllerTest` (Story 4.2, `@Import({SecurityConfig.class, JwtAuthenticationFilter.class})`, `@MockitoBean QueryAuditLogUseCase`):
    - `searchWithAdminRoleReturns200AndEntries`
    - `searchWithUserRoleReturns403ProblemJson` (mismo assert de `$.detail` == "Access denied." que el análogo de `AdminControllerTest`)
    - `searchWithInvalidAccountIdReturns400ProblemJson` — `when(queryAuditLogUseCase.search(...)).thenThrow(new DomainValidationException(...))`
    - `missingAuthorizationHeaderReturns401ProblemJson`

- [x] Task 7: Test de integración — `AdminAuditLogIntegrationTest` (AC: #1, #2, #4)
  - [x] Nuevo `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogIntegrationTest.java`, mismo patrón Testcontainers que `AdminAccountsIntegrationTest` (Story 4.2 — `@SpringBootTest(RANDOM_PORT)`, `@AutoConfigureMockMvc`, `@Testcontainers(disabledWithoutDocker = true)`, `@Transactional`, JWT de test con `Account.registerAdmin` + `accountRepository.save`). **Igual que en Story 4.2, llamar `entityManager.flush()` tras cada `accountRepository.save(...)`/inserción de fixtures de `audit_log`** — si no, el mismo bug de ordering de flush entre requests HTTP dentro de la misma transacción de test que se corrigió en Story 4.2 puede reaparecer aquí.
    - `searchFiltersByAccountIdReturnsOnlyEntriesWhereItWasTarget` — persistir 2+ filas de `audit_log` (vía `AuditLogJpaRepository`, con `entityManager.flush()`) con distinto `target_account_id`, verificar que solo vuelven las de la Cuenta filtrada.
    - `searchFiltersByDateRangeReturnsOnlyEntriesWithinRange` — filas con `occurredAt` dentro y fuera del rango `[from, to]`.
    - `searchOrdersResultsByOccurredAtDescending`
    - `searchWithNoFiltersReturnsAllEntries`
    - `userRoleGetsForbiddenOnAuditLogEndpoint`
  - [x] Suite completa (`./mvnw -B verify`) sin regresión en Epics 1, 2, 3 y Stories 4.1/4.2.

### Review Findings

- [x] [Review][Patch] `AuditLogJpaRepository.search` usa `SELECT *` en la query nativa, dependiendo del mapeo implícito columna→campo de Hibernate — frágil ante un futuro reorder/rename de columnas en `audit_log` o campos en `AuditLogEntity` (fallaría en runtime, no en compilación) [src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java:20] — aplicado: columnas explícitas
- [x] [Review][Patch] `AdminAuditLogControllerTest` omite el `verify(queryAuditLogUseCase, never()).search(...)` que el patrón replicado (`AdminControllerTest`, Story 4.2) sí incluye en sus casos análogos de 403/401 — sin riesgo funcional (el filtro de seguridad bloquea el dispatch antes de invocar el use case en cualquier caso), pero es una desviación menor de fidelidad respecto al patrón que la Task 6 pide replicar [src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogControllerTest.java:339,359] — aplicado: `verify(never())` agregado a ambos tests
- [x] [Review][Defer] Sin paginación en `GET /api/v1/admin/audit-log` — deferred, pre-existing (decisión consciente ya documentada como `[ASSUMPTION]` en los Dev Notes de esta historia; riesgo real solo si el volumen de filas crece sin uso de filtros)
- [x] [Review][Defer] Sin límite máximo en el rango `from`/`to` — un rango sin acotar es funcionalmente equivalente a no filtrar, mismo riesgo raíz que la falta de paginación — deferred, pre-existing
- [x] [Review][Defer] `V6__audit_log_query_indexes.sql` crea dos índices de una sola columna en vez de un índice compuesto `(target_account_id, occurred_at DESC)` más ajustado a la forma real de la query — deferred, pre-existing (SQL literal ya especificado por la Task 1 de esta historia)
- [x] [Review][Defer] Los `CREATE INDEX` de `V6__audit_log_query_indexes.sql` no usan `CONCURRENTLY` — bloquearía escrituras sobre `audit_log` durante la migración una vez la tabla tenga volumen real de producción — deferred, pre-existing
- [x] [Review][Defer] Consultar el registro de auditoría no queda en sí mismo auditado — ningún `AuditLogEntry` se escribe cuando un Administrador llama a `GET /api/v1/admin/audit-log` — deferred, pre-existing (fuera de alcance de los AC de esta historia)
- [x] [Review][Defer] Boilerplate de JWT de test (`SECRET`, `tokenFor`, bloque `TestPropertySource`) duplicado por tercera vez en `AdminAuditLogControllerTest`/`AdminAuditLogIntegrationTest`, replicando el mismo patrón que `AdminControllerTest`/`AdminAccountsIntegrationTest` — sin utilidad de test compartida extraída — deferred, pre-existing
- [x] [Review][Defer] `PostgreSQLContainer` fijado solo a versión mayor (`"postgres:15"`), no a minor/patch/digest — convención ya existente en el proyecto, riesgo de flakiness si la imagen upstream cambia — deferred, pre-existing [src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogIntegrationTest.java:435]
- [x] [Review][Defer] `AuditLogRepositoryAdapter.toDomain` llama `AdminAction.valueOf`/`AuditResult.valueOf` sobre los strings persistidos sin ninguna guarda — si alguno de esos enums pierde o renombra una constante en el futuro, las filas históricas con el valor antiguo harían que `search()` lance una excepción no controlada y oculte todos los resultados, en vez de degradar con gracia — riesgo hoy inerte (ninguna fila actual puede fallar así), deferred, pre-existing [src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java:35-39]

## Dev Notes

- **AC #4 (append-only) no requiere ningún código nuevo en esta historia.** El trigger `audit_log_append_only` (`BEFORE UPDATE OR DELETE`) y `audit_log_append_only_truncate` (`BEFORE TRUNCATE`) de `V5__audit_log.sql` (Story 4.2) ya garantizan esto a nivel de esquema. **No intentar además un `REVOKE UPDATE, DELETE` sobre el rol de aplicación** — el propio comentario de `V5__audit_log.sql` ya documenta por qué no funciona en este proyecto (el rol de aplicación es también el dueño de la tabla; PostgreSQL no permite que un dueño se quite privilegios DML sobre lo que posee). Si se relee el AC #3 original de la Story 4.3 en `epics.md` ("el rol de aplicación de PostgreSQL no tiene permiso UPDATE/DELETE") literalmente, es técnicamente impreciso frente a esa topología de un solo rol — la garantía real es el trigger, no el permiso de rol. No es una regresión ni algo que corregir aquí; ya fue una decisión consciente de la Story 4.2.
- **`search` solo filtra por `target_account_id`, nunca por `actor_account_id`.** El AC #1 y el erDiagram del architecture spine (`ACCOUNT ||--o{ AUDIT_LOG : "0..n, como target"`) son explícitos: la pregunta que esta historia resuelve es "qué pasó con esta Cuenta", no "qué hizo este Administrador". No inventar un segundo filtro `actorId` — no está pedido por ningún AC.
- **Sin paginación en este endpoint.** [ASSUMPTION] A diferencia de `GET /api/v1/admin/accounts` (Story 4.2, FR-11, que sí pide "listado paginado" explícitamente), ningún AC de esta historia ni el FR-13 del PRD piden paginación para el registro de auditoría — el PRD dice literalmente "filtros mínimos" (accountId o rango de fechas). Añadir paginación no pedida sería scope creep (mismo criterio que Story 4.2 aplicó para descartar la protección de "último Admin"). Es un riesgo real si el volumen de filas crece sin que nunca se use un filtro — ya queda cubierto en parte por los índices de la Task 1; si en producción se vuelve un problema real, es candidato a `deferred-work.md`, no a resolverse por adivinanza aquí.
- **`Instant` cruza el port `AuditLogRepository` sin violar AD-1** — `java.time.Instant` es JDK puro (no es un tipo de Spring/JPA), igual que ya lo usa `AuditLogEntry`/`Account` en `domain/model`. Lo que NO debe cruzar es `Pageable`/`Sort`/`Specification` de Spring Data — el filtrado y el orden se expresan con tipos planos en el port, igual que `AccountRepository.findAllPaged(int page, int size)` ya hace en Story 4.2.
- **Reutilizar el patrón de parseo defensivo ya establecido, no reinventarlo:** `ManageAccountUseCase.parseActorId`/`findTarget` (Story 4.2) ya resuelven "String posiblemente inválido → UUID o error de dominio explícito". `QueryAuditLogUseCase` replica el mismo patrón para `accountId`, y lo extiende de forma simétrica para `from`/`to` con `Instant.parse`.
- **`AuditLogJpaRepository.search` es el primer `@Query` JPQL explícito del proyecto** — todos los repos anteriores (`AccountJpaRepository`, `RefreshTokenJpaRepository`, etc.) usan derived-query-methods porque nunca necesitaron combinar filtros opcionales. No generalizar este patrón a otros repos como parte de esta historia — está motivado únicamente por la necesidad real de `search` (3 filtros combinables, cualquier subconjunto activo).

### Project Structure Notes

- **Nuevos:**
  - `src/main/resources/db/migration/V6__audit_log_query_indexes.sql`
  - `src/main/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCase.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogController.java`
  - `src/main/java/com/auth_service/auth/infrastructure/controller/dto/AuditLogEntryResponse.java`
  - `src/test/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCaseTest.java`
  - `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogControllerTest.java`
  - `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogIntegrationTest.java`
- **Modificados:**
  - `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java` (+`search`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java` (+`search` con `@Query`)
  - `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java` (+implementación de `search`, +mapeo entity→domain)
- **Sin cambios:** `AdminController`, `ManageAccountUseCase`, `V5__audit_log.sql` (el trigger append-only ya cubre AC #4), `SecurityConfig`, `GlobalExceptionHandler` (los handlers de 403/400 que esta historia necesita ya existen desde Stories 1.2 y 4.2).

### References

- [Source: docs/planning-artifacts/epics.md#Story-4.3] — Story 4.3 completa (AC Given/When/Then), FR-13
- [Source: docs/planning-artifacts/prds/prd-auth-service-2026-07-01/prd.md#FR-13] — "filtros mínimos" `[ASSUMPTION]`, append-only
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-20] — auditoría inmutable, append-only
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#AD-18] — `/api/v1/admin/**`, versionado explícito
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#erDiagram] — `AUDIT_LOG` con `target_account_id` como eje de consulta
- [Source: docs/planning-artifacts/architecture/architecture-auth-service-2026-07-01/ARCHITECTURE-SPINE.md#Capability→Architecture-Map] — "FR-13 | AUDIT_LOG, escrito por ManageAccountUseCase | AD-20" (esta historia añade el lado de lectura)
- [Source: docs/implementation-artifacts/4-2-gestion-administrativa-de-cuentas-con-auditoria.md] — `AuditLogEntry`/`AuditLogEntity`/`AuditLogRepository`/`AuditLogRepositoryAdapter` ya existentes a extender; patrón `ManageAccountUseCase.parseActorId`/`findTarget` a replicar; patrón `AdminController`/`AdminControllerTest`/`AdminAccountsIntegrationTest` a replicar por separado para el nuevo recurso
- [Source: docs/implementation-artifacts/deferred-work.md#Story-4-2] — entrada "Sin índice en `audit_log` para `target_account_id`/`occurred_at`" — motiva la Task 1 de esta historia
- [Source: src/main/resources/db/migration/V5__audit_log.sql] — trigger append-only existente (AC #4 ya cubierto), última migración (`V6` es la siguiente)
- [Source: src/main/java/com/auth_service/auth/application/usecase/ManageAccountUseCase.java] — patrón de parseo defensivo (`parseActorId`, `findTarget`) a replicar
- [Source: src/main/java/com/auth_service/auth/infrastructure/controller/AdminController.java] — patrón controller→use case→DTO, `@PreAuthorize` a nivel de clase
- [Source: src/main/java/com/auth_service/auth/infrastructure/controller/GlobalExceptionHandler.java] — handlers de `DomainValidationException`→400 y `AccessDeniedException`→403 ya existentes, sin cambios necesarios
- [Source: src/test/java/com/auth_service/auth/infrastructure/controller/AdminControllerTest.java] y [Source: src/test/java/com/auth_service/auth/infrastructure/controller/AdminAccountsIntegrationTest.java] — patrones de test slice/integración a replicar

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- **Desvío respecto al Task 2 tal como está escrito:** el `@Query` JPQL propuesto en la historia (`(:targetAccountId IS NULL OR a.targetAccountId = :targetAccountId) AND ...`) falla en tiempo de ejecución contra PostgreSQL con `ERROR: could not determine data type of parameter $3`. Causa: cada aparición textual de un parámetro nombrado de Spring Data se traduce a un placeholder `?` posicional independiente en la sentencia preparada — Postgres resuelve el tipo de cada posición por separado, y la posición `? IS NULL` (sin ningún otro contexto de tipo en esa misma posición) no da pista suficiente. Verificado con logging SQL de Hibernate contra Testcontainers.
  - **Fix aplicado:** `AuditLogJpaRepository.search` pasó de JPQL a `@Query(nativeQuery = true)` con `CAST(:param AS uuid|timestamptz)` en **ambas** apariciones de cada parámetro (la de `IS NULL` y la de comparación), no solo una. Con el cast solo en una aparición el error persistía en la otra.
  - Verificado con `AdminAuditLogIntegrationTest` completo (5/5 tests) contra PostgreSQL real vía Testcontainers.

### Completion Notes List

- Las 4 ACs implementadas y verificadas: filtro por `accountId` (AC#1), filtro por rango `from`/`to` (AC#2, inclusive en ambos extremos, combinable con `accountId`), 403 para Rol `USER` (AC#3), AC#4 heredado sin cambios del trigger append-only de la Story 4.2.
- Índices de la Task 1 (`V6__audit_log_query_indexes.sql`) aplicados — cierra el ítem diferido de `deferred-work.md` desde la Story 4.2.
- Suite completa verde: 348 tests, 0 fallas, 0 errores (`./mvnw test` con Testcontainers vía Docker).
- Ningún archivo fuera del alcance de esta historia fue modificado (`AdminController`, `ManageAccountUseCase`, `V5__audit_log.sql`, `SecurityConfig`, `GlobalExceptionHandler` permanecen sin cambios, tal como anticipaban los Dev Notes).

### File List

**Nuevos:**
- `src/main/resources/db/migration/V6__audit_log_query_indexes.sql`
- `src/main/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCase.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogController.java`
- `src/main/java/com/auth_service/auth/infrastructure/controller/dto/AuditLogEntryResponse.java`
- `src/test/java/com/auth_service/auth/application/usecase/QueryAuditLogUseCaseTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogControllerTest.java`
- `src/test/java/com/auth_service/auth/infrastructure/controller/AdminAuditLogIntegrationTest.java`

**Modificados:**
- `src/main/java/com/auth_service/auth/domain/port/AuditLogRepository.java` (+`search`)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogJpaRepository.java` (+`search`, `@Query(nativeQuery = true)` con casts explícitos — ver Debug Log References)
- `src/main/java/com/auth_service/auth/infrastructure/adapters/postgresql/AuditLogRepositoryAdapter.java` (+implementación de `search`, +mapeo entity→domain)

## Change Log

- 2026-07-22: Implementación inicial completa (Tasks 1-7). Desvío documentado en Debug Log References: `AuditLogJpaRepository.search` usa `@Query(nativeQuery = true)` en vez del JPQL propuesto en la historia, por una limitación de inferencia de tipos de PostgreSQL con parámetros en posición `IS NULL`.
