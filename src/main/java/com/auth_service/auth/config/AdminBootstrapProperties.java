package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciales del Administrador inicial (FR-12, AD-10, NFR-7) — nunca
 * {@code @Value} disperso. {@code auth.admin.email}/{@code password} en
 * {@code application.properties}, mapeadas desde {@code AUTH_ADMIN_EMAIL}/
 * {@code AUTH_ADMIN_PASSWORD}. A diferencia de {@link JwtProperties} o
 * {@link AuthTokenProperties}, ambos valores son opcionales a nivel de
 * esta clase — el arranque sin ellos es un escenario válido (AC #3 de la
 * Story 4.1); quien decide si eso amerita una advertencia es
 * {@code AdminBootstrapRunner}, no este record.
 */
@ConfigurationProperties(prefix = "auth.admin")
public record AdminBootstrapProperties(String email, String password) {
}
