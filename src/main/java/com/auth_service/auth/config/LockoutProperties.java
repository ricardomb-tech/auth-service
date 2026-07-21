package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Umbral y duración del bloqueo por fuerza bruta configurables por entorno
 * (NFR-7) — nunca {@code @Value} disperso. {@code auth.lockout.threshold}/
 * {@code lock-duration} en {@code application.properties}.
 */
@ConfigurationProperties(prefix = "auth.lockout")
public record LockoutProperties(Integer threshold, Duration lockDuration) {

    public LockoutProperties {
        if (threshold == null) {
            threshold = 5;
        } else if (threshold <= 0) {
            throw new IllegalStateException("auth.lockout.threshold debe ser mayor que cero.");
        }
        if (lockDuration == null) {
            lockDuration = Duration.ofMinutes(15);
        } else if (lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalStateException("auth.lockout.lock-duration debe ser una duración positiva.");
        }
    }
}
