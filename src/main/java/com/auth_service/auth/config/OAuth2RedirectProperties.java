package com.auth_service.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * URLs de redirección del frontend tras el flujo OAuth2 (NFR-7, Story 2.1) —
 * nunca hardcodeadas. El servicio es API-first, sin UI propia de login (ver
 * Dev Notes de la Story 2.1): el success/failure handler termina siempre en
 * una redirección de navegador hacia una de estas dos URLs configurables.
 */
@ConfigurationProperties(prefix = "auth.oauth2")
public record OAuth2RedirectProperties(String successRedirectUri, String failureRedirectUri) {

    public OAuth2RedirectProperties {
        if (successRedirectUri == null || successRedirectUri.isBlank()) {
            throw new IllegalStateException("auth.oauth2.success-redirect-uri es obligatorio.");
        }
        if (failureRedirectUri == null || failureRedirectUri.isBlank()) {
            throw new IllegalStateException("auth.oauth2.failure-redirect-uri es obligatorio.");
        }
    }
}
