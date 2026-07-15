package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.config.OAuth2RedirectProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Cubre el fallo/cancelación en el propio proveedor (AC #3, Story 2.1) — p.
 * ej. el visitante deniega el consentimiento en Google, o Spring Security
 * rechaza el intercambio del código. Redirige al navegador a una URL de
 * frontend configurable (NFR-7) con un código de error genérico, nunca el
 * detalle de la excepción — mismo principio de indistinguibilidad que
 * {@code GlobalExceptionHandler}/{@code SecurityConfig} ya aplican (AD-8).
 */
@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final OAuth2RedirectProperties redirectProperties;

    public OAuth2AuthenticationFailureHandler(OAuth2RedirectProperties redirectProperties) {
        this.redirectProperties = redirectProperties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String redirectUrl = UriComponentsBuilder.fromUriString(redirectProperties.failureRedirectUri())
                .queryParam("error", "federated_login_failed")
                .encode()
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }
}
