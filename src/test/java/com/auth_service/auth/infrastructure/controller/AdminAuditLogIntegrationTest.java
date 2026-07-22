package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1, #2, #4 de la Story 4.3.
 * Mismo patrón que {@code AdminAccountsIntegrationTest} (Story 4.2).
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
class AdminAuditLogIntegrationTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

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

    private void persistAuditRow(AccountId actorId, AccountId targetId, AdminAction action, Instant occurredAt) {
        auditLogJpaRepository.save(new AuditLogEntity(UUID.randomUUID(), actorId.value(), targetId.value(),
                action.name(), AuditResult.SUCCESS.name(), occurredAt));
        entityManager.flush();
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
    void searchFiltersByAccountIdReturnsOnlyEntriesWhereItWasTarget() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetA = persistActiveAccount("objetivo-a@example.com");
        AccountId targetB = persistActiveAccount("objetivo-b@example.com");
        persistAuditRow(adminId, targetA, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-07-01T00:00:00Z"));
        persistAuditRow(adminId, targetB, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-07-02T00:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/audit-log?accountId=" + targetA.value())
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetAccountId").value(targetA.value().toString()));
    }

    @Test
    void searchFiltersByDateRangeReturnsOnlyEntriesWithinRange() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("objetivo@example.com");
        persistAuditRow(adminId, targetId, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-06-01T00:00:00Z"));
        persistAuditRow(adminId, targetId, AdminAction.REACTIVATE_ACCOUNT, Instant.parse("2026-07-15T00:00:00Z"));
        persistAuditRow(adminId, targetId, AdminAction.UPDATE_ROLES, Instant.parse("2026-08-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/audit-log?from=2026-07-01T00:00:00Z&to=2026-07-31T23:59:59Z")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("REACTIVATE_ACCOUNT"));
    }

    @Test
    void searchOrdersResultsByOccurredAtDescending() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetId = persistActiveAccount("objetivo@example.com");
        persistAuditRow(adminId, targetId, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-07-01T00:00:00Z"));
        persistAuditRow(adminId, targetId, AdminAction.REACTIVATE_ACCOUNT, Instant.parse("2026-07-03T00:00:00Z"));
        persistAuditRow(adminId, targetId, AdminAction.UPDATE_ROLES, Instant.parse("2026-07-02T00:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/audit-log?accountId=" + targetId.value())
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("REACTIVATE_ACCOUNT"))
                .andExpect(jsonPath("$[1].action").value("UPDATE_ROLES"))
                .andExpect(jsonPath("$[2].action").value("DISABLE_ACCOUNT"));
    }

    @Test
    void searchWithNoFiltersReturnsAllEntries() throws Exception {
        AccountId adminId = persistAdmin("admin@example.com");
        AccountId targetA = persistActiveAccount("objetivo-a@example.com");
        AccountId targetB = persistActiveAccount("objetivo-b@example.com");
        persistAuditRow(adminId, targetA, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-07-01T00:00:00Z"));
        persistAuditRow(adminId, targetB, AdminAction.DISABLE_ACCOUNT, Instant.parse("2026-07-02T00:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tokenFor(adminId, List.of("ADMIN", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void userRoleGetsForbiddenOnAuditLogEndpoint() throws Exception {
        AccountId userId = persistActiveAccount("solo-user@example.com");

        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .header("Authorization", "Bearer " + tokenFor(userId, List.of("USER"))))
                .andExpect(status().isForbidden());
    }
}
