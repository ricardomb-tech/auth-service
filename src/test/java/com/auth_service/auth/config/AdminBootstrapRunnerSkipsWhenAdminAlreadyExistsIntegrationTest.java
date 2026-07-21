package com.auth_service.auth.config;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.infrastructure.adapters.postgresql.AccountEntity;
import com.auth_service.auth.infrastructure.adapters.postgresql.AccountJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.ApplicationRunner;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC #2 de la Story 4.1 — si ya existe al menos una Cuenta ADMIN, el arranque
 * no crea ni modifica ninguna Cuenta, aunque AUTH_ADMIN_EMAIL/PASSWORD estén
 * definidos.
 *
 * <p><b>Decisión de diseño (ver Dev Agent Record → Completion Notes):</b> los
 * {@link ApplicationRunner} de Spring Boot corren automáticamente al levantar
 * el {@code ApplicationContext}, antes de que el cuerpo de un {@code @Test}
 * pueda pre-sembrar nada — no es posible usar el patrón habitual de este
 * proyecto ({@code persistAccount(...)} vía {@code @Autowired
 * AccountRepository} dentro del test). En su lugar, esta clase declara un
 * {@code @TestConfiguration} anidado con un segundo {@link ApplicationRunner}
 * anotado {@code @Order(Ordered.HIGHEST_PRECEDENCE)} — Spring Boot ejecuta
 * los {@code ApplicationRunner} en orden ascendente de {@code @Order}, y
 * {@link AdminBootstrapRunner} no declara ningún orden explícito (se resuelve
 * como {@code LOWEST_PRECEDENCE} en la comparación), así que este runner de
 * siembra siempre corre primero y deja persistida la Cuenta ADMIN
 * preexistente antes de que {@link AdminBootstrapRunner} evalúe
 * {@code existsByRole(ADMIN)}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "auth.oauth2.success-redirect-uri=http://localhost/ok",
        "auth.oauth2.failure-redirect-uri=http://localhost/fail",
        "auth.admin.email=admin@example.com",
        "auth.admin.password=Str0ngAdminPass1"
})
class AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest {

    private static final String PREEXISTING_ADMIN_EMAIL = "existing-admin@example.com";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountJpaRepository accountJpaRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @TestConfiguration
    static class SeedExistingAdminConfig {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner seedExistingAdminRunner(AccountRepository accountRepository, PasswordHasher passwordHasher) {
            return args -> {
                HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngExistingPass1"));
                Account existingAdmin = Account.registerAdmin(new Email(PREEXISTING_ADMIN_EMAIL), hashedPassword);
                accountRepository.save(existingAdmin);
            };
        }
    }

    @Test
    void noNewAdminIsCreatedAndThePreexistingOneIsUntouched() {
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isTrue();

        Optional<Account> configuredEmailAccount = accountRepository.findByEmail(new Email("admin@example.com"));
        assertThat(configuredEmailAccount).isEmpty();

        Optional<Account> preexistingAdmin = accountRepository.findByEmail(new Email(PREEXISTING_ADMIN_EMAIL));
        assertThat(preexistingAdmin).hasValueSatisfying(account -> {
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
            assertThat(passwordHasher.matches("Str0ngExistingPass1", account.passwordHash())).isTrue();
        });

        // AccountRepository (Review Findings, Story 4.1) solo expone
        // existsByRole (booleano) — un conteo directo vía el JpaRepository
        // confirma que AdminBootstrapRunner no creó un segundo ADMIN además
        // del preexistente (Task 6 lo preveía como fallback si el port no
        // alcanzaba).
        long adminCount = accountJpaRepository.findAll().stream()
                .map(AccountEntity::getRoles)
                .filter(roles -> roles.contains(Role.ADMIN))
                .count();
        assertThat(adminCount).isEqualTo(1);
    }
}
