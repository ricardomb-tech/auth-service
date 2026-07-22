package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import com.auth_service.auth.infrastructure.adapters.postgresql.AuditLogEntity;
import com.auth_service.auth.infrastructure.adapters.postgresql.AuditLogJpaRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1..#5 de la
 * Story 4.2. El Access Token ADMIN se obtiene creando directamente una
 * Cuenta ADMIN vía {@link Account#registerAdmin} (Story 4.1) y firmando un
 * JWT de test — no vía {@code POST /auth/login} real, para no acoplar este
 * test al flujo de login (el login en sí no es lo que se está probando).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.google.scope=openid,email,profile"
})
class AdminAccountsIntegrationTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private AccountId persistAdmin(String email) {
        Account admin = Account.registerAdmin(new Email(email), passwordHasher.hash(new RawPassword("Str0ngAdminPass1")));
        AccountId id = accountRepository.save(admin).id();
        entityManager.flush();
        return id;
    }

    private AccountId persistActiveAccount(String email) {
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), passwordHasher.hash(new RawPassword("Str0ngPass1")),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        AccountId id = accountRepository.save(account).id();
        entityManager.flush();
        return id;
    }

    private String tokenFor(AccountId subject, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant expiration = Instant.now().plus(Duration.ofMinutes(15));
        return Jwts.builder()
                .subject(subject.value().toString())
                .claim("email", "admin@example.com")
                .claim("roles", roles)
                .issuedAt(Date.from(expiration.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(expiration))
                .issuer("auth-service")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void adminListsAccountsPaginated() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        persistActiveAccount("uno@example.com");
        persistActiveAccount("dos@example.com");
        persistActiveAccount("tres@example.com");

        mockMvc.perform(get("/api/v1/admin/accounts?page=0&size=2")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void userRoleGetsForbiddenOnAnyAdminEndpoint() throws Exception {
        AccountId userId = persistActiveAccount("solo-user@example.com");

        mockMvc.perform(get("/api/v1/admin/accounts")
                        .header("Authorization", "Bearer " + tokenFor(userId, List.of("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDisablesActiveAccountRevokesTokensAndWritesAuditLog() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("objetivo@example.com");
        RefreshToken.Issued issued = RefreshToken.issue(targetId, UUID.randomUUID(), Duration.ofDays(7), Clock.systemUTC());
        refreshTokenRepository.save(issued.token());

        mockMvc.perform(post("/api/v1/admin/accounts/" + targetId.value() + "/disable")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        Optional<Account> updated = accountRepository.findById(targetId);
        assertThat(updated).hasValueSatisfying(a -> assertThat(a.status()).isEqualTo(AccountStatus.DISABLED));

        Optional<RefreshToken> token = refreshTokenRepository.findByTokenHash(issued.token().tokenHash());
        assertThat(token).hasValueSatisfying(t -> assertThat(t.revokedAt()).isNotNull());

        List<AuditLogEntity> auditRows = auditLogJpaRepository.findAll();
        assertThat(auditRows).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo(AdminAction.DISABLE_ACCOUNT.name());
            assertThat(row.getResult()).isEqualTo(AuditResult.SUCCESS.name());
            assertThat(row.getActorAccountId()).isEqualTo(adminId.value());
            assertThat(row.getTargetAccountId()).isEqualTo(targetId.value());
        });
    }

    @Test
    void disabledAccountCannotLoginWithCredentials() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("objetivo2@example.com");

        mockMvc.perform(post("/api/v1/admin/accounts/" + targetId.value() + "/disable")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"objetivo2@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminReactivatesDisabledAccountAndWritesAuditLog() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        Account disabledAccount = Account.reconstitute(AccountId.newId(), new Email("desactivado@example.com"),
                passwordHasher.hash(new RawPassword("Str0ngPass1")), AccountStatus.DISABLED, Set.of(Role.USER), 0, null, Instant.now());
        AccountId targetId = accountRepository.save(disabledAccount).id();

        mockMvc.perform(post("/api/v1/admin/accounts/" + targetId.value() + "/reactivate")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Optional<Account> updated = accountRepository.findById(targetId);
        assertThat(updated).hasValueSatisfying(a -> assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE));

        List<AuditLogEntity> auditRows = auditLogJpaRepository.findAll();
        assertThat(auditRows).anySatisfy(row -> assertThat(row.getAction()).isEqualTo(AdminAction.REACTIVATE_ACCOUNT.name()));
    }

    @Test
    void adminCannotDisableSelfAndAttemptIsAudited() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");

        mockMvc.perform(post("/api/v1/admin/accounts/" + adminId.value() + "/disable")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isConflict());

        Optional<Account> stillActive = accountRepository.findById(adminId);
        assertThat(stillActive).hasValueSatisfying(a -> assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE));

        List<AuditLogEntity> auditRows = auditLogJpaRepository.findAll();
        assertThat(auditRows).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo(AdminAction.DISABLE_ACCOUNT.name());
            assertThat(row.getResult()).isEqualTo(AuditResult.REJECTED_SELF.name());
        });
    }

    @Test
    void adminCannotRemoveOwnAdminRoleAndAttemptIsAudited() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");

        mockMvc.perform(put("/api/v1/admin/accounts/" + adminId.value() + "/roles")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"USER\"]}"))
                .andExpect(status().isConflict());

        Optional<Account> stillAdmin = accountRepository.findById(adminId);
        assertThat(stillAdmin).hasValueSatisfying(a -> assertThat(a.roles()).contains(Role.ADMIN));

        List<AuditLogEntity> auditRows = auditLogJpaRepository.findAll();
        assertThat(auditRows).anySatisfy(row -> {
            assertThat(row.getAction()).isEqualTo(AdminAction.UPDATE_ROLES.name());
            assertThat(row.getResult()).isEqualTo(AuditResult.REJECTED_SELF.name());
        });
    }

    // Task 8 pide un único test "auditLogRejectsDirectUpdateAndDeleteAtDatabaseLevel",
    // pero UPDATE y DELETE nativos no pueden verificarse en la misma
    // transacción: Postgres deja la transacción JDBC "aborted" tras el primer
    // rechazo (todo error dentro de una transacción la envenena hasta el
    // próximo ROLLBACK), y el JpaDialect de este proyecto no soporta
    // savepoints (PROPAGATION_NESTED falla con "JpaDialect does not support
    // savepoints"). Separarlo en dos @Test evita el problema por completo:
    // cada método recibe su propia transacción de test, descartada al final.
    @Test
    void auditLogRejectsDirectUpdateAtDatabaseLevel() {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("otra-cuenta@example.com");
        UUID auditRowId = UUID.randomUUID();
        entityManager.persist(new AuditLogEntity(auditRowId, adminId.value(), targetId.value(),
                AdminAction.DISABLE_ACCOUNT.name(), AuditResult.SUCCESS.name(), Instant.now()));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("UPDATE audit_log SET result = 'REJECTED_SELF' WHERE id = ?1")
                    .setParameter(1, auditRowId)
                    .executeUpdate();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void auditLogRejectsDirectDeleteAtDatabaseLevel() {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("otra-cuenta@example.com");
        UUID auditRowId = UUID.randomUUID();
        entityManager.persist(new AuditLogEntity(auditRowId, adminId.value(), targetId.value(),
                AdminAction.DISABLE_ACCOUNT.name(), AuditResult.SUCCESS.name(), Instant.now()));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("DELETE FROM audit_log WHERE id = ?1")
                    .setParameter(1, auditRowId)
                    .executeUpdate();
        }).isInstanceOf(RuntimeException.class);
    }
}
