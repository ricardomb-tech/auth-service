package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs de tokens configurables por entorno (AD-10, NFR-7) — nunca
 * {@code @Value} disperso. {@code auth.token.verification-ttl} en
 * {@code application.properties}.
 */
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(Duration verificationTtl) {

    public AuthTokenProperties {
        if (verificationTtl == null) {
            verificationTtl = Duration.ofHours(24);
        }
    }
}
