package com.auth_service.auth.config;

import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AC #3 de la Story 4.1 — sin AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD definidos
 * y sin ninguna Cuenta ADMIN preexistente, el arranque no falla (solo
 * registra una advertencia — no se afirma nada sobre el contenido exacto del
 * mensaje de log, sería frágil) y no crea ninguna Cuenta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
        "auth.oauth2.success-redirect-uri=http://localhost/ok",
        "auth.oauth2.failure-redirect-uri=http://localhost/fail",
        "auth.admin.email=",
        "auth.admin.password="
})
class AdminBootstrapRunnerWarnsAndStartsWhenConfigurationMissingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void contextStartsWithoutFailureAndNoAdminAccountIsCreated() {
        assertThatCode(() -> context.getBean(AdminBootstrapRunner.class)).doesNotThrowAnyException();
        assertThat(accountRepository.existsByRole(Role.ADMIN)).isFalse();
    }
}
