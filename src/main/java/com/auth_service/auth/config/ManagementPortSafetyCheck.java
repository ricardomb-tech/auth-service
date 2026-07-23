package com.auth_service.auth.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guard de arranque (Review Findings, Story 5.1, decisión 1): {@link
 * SecurityConfig#actuatorSecurityFilterChain} da {@code permitAll()}
 * incondicional a todo {@code /actuator/**}, asumiendo que ese puerto está
 * físicamente separado del puerto de negocio (AD-11, AD-16). Si {@code
 * server.port} y {@code management.server.port} llegaran a resolver al
 * mismo valor — p. ej. por una variable de entorno mal puesta en producción
 * ({@code MANAGEMENT_SERVER_PORT}) — Spring Boot fusiona el contexto de
 * management en el de negocio, y esa cadena {@code permitAll} pasaría a
 * aplicarse también ahí, exponiendo {@code /actuator/**} (incluyendo
 * {@code show-details=always}) sin autenticación en el puerto público.
 *
 * <p>{@code 0} en cualquiera de los dos valores no cuenta como colisión:
 * es la convención estándar de Spring Boot ("puerto aleatorio libre
 * asignado por el SO"), y ambos servidores embebidos reciben puertos
 * distintos aunque los dos se configuren en {@code 0} — el patrón que ya
 * usan {@code ActuatorHealthIntegrationTest}/{@code
 * StructuredLoggingIntegrationTest} vía {@code RANDOM_PORT} +
 * {@code management.server.port=0}.
 */
@Component
public class ManagementPortSafetyCheck {

    private final int serverPort;
    private final int managementPort;

    public ManagementPortSafetyCheck(@Value("${server.port:8080}") int serverPort,
                                      @Value("${management.server.port}") int managementPort) {
        this.serverPort = serverPort;
        this.managementPort = managementPort;
    }

    @PostConstruct
    void verifyPortsAreDistinct() {
        if (serverPort != 0 && serverPort == managementPort) {
            throw new IllegalStateException(
                    "server.port y management.server.port no pueden coincidir (ambos=" + serverPort + "): "
                            + "el puerto de management expone /actuator/** sin autenticación (AD-11, AD-16); "
                            + "si coincidiera con el puerto de negocio, esa exposición se aplicaría también ahí.");
        }
    }
}
