package com.auth_service.auth.config;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC #1 de la Story 4.1 — sin ninguna Cuenta ADMIN preexistente y con
 * AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD definidos, {@link AdminBootstrapRunner}
 * crea la Cuenta ADMIN inicial durante el arranque del ApplicationContext.
 * No hay endpoint HTTP que verificar (Dev Notes) — el "output" es el estado
 * de BD tras levantar el contexto, por eso no hay MockMvc.
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
class AdminBootstrapRunnerCreatesAdminWhenNoneExistsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Test
    void adminAccountIsCreatedActiveWithBothRolesAndBcryptPassword() {
        Optional<Account> admin = accountRepository.findByEmail(new Email("admin@example.com"));

        assertThat(admin).isPresent();
        assertThat(admin.get().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(admin.get().roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        assertThat(passwordHasher.matches("Str0ngAdminPass1", admin.get().passwordHash())).isTrue();
    }
}
