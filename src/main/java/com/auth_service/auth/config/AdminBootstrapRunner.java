package com.auth_service.auth.config;

import com.auth_service.auth.application.usecase.ProvisionInitialAdminResult;
import com.auth_service.auth.application.usecase.ProvisionInitialAdminUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FR-12 — dispara el aprovisionamiento del primer Administrador en cada
 * arranque. Es deliberadamente un {@link ApplicationRunner} en {@code
 * config/} y no un caso de uso: no orquesta ninguna mutación por sí mismo
 * (eso vive en {@link ProvisionInitialAdminUseCase}, AD-6) — solo decide
 * qué loggear según el resultado, igual que un controller traduce el
 * resultado de un caso de uso a una respuesta HTTP.
 *
 * <p>{@code @Order(LOWEST_PRECEDENCE)}: explícito en vez de implícito
 * (Review Findings, Story 4.1) — algunos tests de integración (p. ej.
 * {@code AdminBootstrapRunnerSkipsWhenAdminAlreadyExistsIntegrationTest})
 * dependen de que este runner corra después de cualquier otro
 * {@link ApplicationRunner} de siembra que declare un {@code @Order} más
 * alto; declararlo aquí hace el contrato visible donde vive el código, no
 * solo en la documentación de la story.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final ProvisionInitialAdminUseCase provisionInitialAdminUseCase;
    private final AdminBootstrapProperties adminBootstrapProperties;

    public AdminBootstrapRunner(ProvisionInitialAdminUseCase provisionInitialAdminUseCase,
                                 AdminBootstrapProperties adminBootstrapProperties) {
        this.provisionInitialAdminUseCase = provisionInitialAdminUseCase;
        this.adminBootstrapProperties = adminBootstrapProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProvisionInitialAdminResult result = provisionInitialAdminUseCase.provision(
                adminBootstrapProperties.email(), adminBootstrapProperties.password());

        switch (result) {
            case ADMIN_CREATED ->
                    log.info("Administrador inicial aprovisionado desde AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD.");
            case ADMIN_ALREADY_EXISTS ->
                    log.debug("Ya existe al menos una Cuenta ADMIN — no se aprovisiona ninguna nueva.");
            case MISSING_CONFIGURATION ->
                    log.warn("AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD no están definidos y no existe ninguna " +
                            "Cuenta ADMIN — el servicio arranca sin administrador inicial. Defina ambas " +
                            "variables y reinicie para aprovisionar el primer Administrador.");
        }
    }
}
