package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs de tokens configurables por entorno (AD-10, NFR-7) — nunca
 * {@code @Value} disperso. {@code auth.token.verification-ttl}/
 * {@code refresh-ttl}/{@code password-reset-ttl} en {@code application.properties}.
 */
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(Duration verificationTtl, Duration refreshTtl, Duration passwordResetTtl) {

    public AuthTokenProperties {
        if (verificationTtl == null) {
            verificationTtl = Duration.ofHours(24);
        }
        if (refreshTtl == null) {
            refreshTtl = Duration.ofDays(7);
        } else if (refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalStateException("auth.token.refresh-ttl debe ser una duración positiva.");
        } else if (refreshTtl.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalStateException("auth.token.refresh-ttl no puede superar 90 días.");
        }
        if (passwordResetTtl == null) {
            passwordResetTtl = Duration.ofHours(1);
        } else if (passwordResetTtl.isZero() || passwordResetTtl.isNegative()) {
            throw new IllegalStateException("auth.token.password-reset-ttl debe ser una duración positiva.");
        } else if (passwordResetTtl.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalStateException("auth.token.password-reset-ttl no puede superar 90 días.");
        }
    }
}
