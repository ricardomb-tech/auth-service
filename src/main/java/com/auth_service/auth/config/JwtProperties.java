package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Secretos y TTL de firma JWT (AD-3, AD-10, AD-19) — nunca {@code @Value}
 * disperso. {@code auth.jwt.secret-current}/{@code secret-previous} soportan
 * la rotación sin downtime: la emisión firma solo con el actual, la
 * validación acepta ambos durante la ventana de rotación.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secretCurrent, String secretPrevious, Duration accessTtl, String issuer) {

    public JwtProperties {
        if (secretCurrent == null || secretCurrent.isBlank()) {
            throw new IllegalStateException("auth.jwt.secret-current es obligatorio.");
        }
        if (accessTtl == null) {
            accessTtl = Duration.ofMinutes(15);
        } else if (accessTtl.isZero() || accessTtl.isNegative()) {
            throw new IllegalStateException("auth.jwt.access-ttl debe ser una duración positiva.");
        } else if (accessTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalStateException("auth.jwt.access-ttl no puede superar 1 día.");
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "auth-service";
        }
    }
}
