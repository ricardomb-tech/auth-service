package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.application.usecase.FederatedLoginCommand;
import com.auth_service.auth.application.usecase.FederatedLoginUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.config.OAuth2RedirectProperties;
import com.auth_service.auth.domain.exception.FederatedLoginFailedException;
import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Extrae del principal OAuth2/OIDC ya autenticado por Spring Security los
 * datos crudos que necesita {@link FederatedLoginCommand} y delega en
 * {@link FederatedLoginUseCase} (AC #1, #2, Story 2.1). En éxito, redirige el
 * navegador a la URL de frontend configurable con un código de intercambio de
 * un solo uso (no con los tokens en sí — revisión de seguridad posterior a la
 * implementación inicial: Access+Refresh Token en query params quedarían en
 * el historial del navegador, en logs de proxy/servidor, y se filtrarían vía
 * {@code Referer}). El frontend canjea ese código por el par real vía
 * {@code POST /auth/oauth2/exchange}. Este servicio es API-first, sin UI
 * propia de login (ver Dev Notes de la Story 2.1), así que no hay una página
 * propia donde renderizar la sesión.
 *
 * <p><b>Contrato a mantener sincronizado:</b> un fallo interno (p. ej. email
 * no verificado por el proveedor) delega en el mismo
 * {@link AuthenticationFailureHandler} que los fallos del propio proveedor —
 * un solo contrato de error hacia el cliente (AD-8), sin bifurcar entre
 * "falló en Google" vs "falló en nuestra validación".</p>
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final FederatedLoginUseCase federatedLoginUseCase;
    private final OAuth2ExchangeCodeStore exchangeCodeStore;
    private final OAuth2RedirectProperties redirectProperties;
    private final AuthenticationFailureHandler failureHandler;

    public OAuth2AuthenticationSuccessHandler(FederatedLoginUseCase federatedLoginUseCase,
                                               OAuth2ExchangeCodeStore exchangeCodeStore,
                                               OAuth2RedirectProperties redirectProperties,
                                               AuthenticationFailureHandler failureHandler) {
        this.federatedLoginUseCase = federatedLoginUseCase;
        this.exchangeCodeStore = exchangeCodeStore;
        this.redirectProperties = redirectProperties;
        this.failureHandler = failureHandler;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User principal = oauthToken.getPrincipal();

        String providerUserId;
        String email;
        boolean emailVerified;
        if (principal instanceof OidcUser oidcUser) {
            providerUserId = oidcUser.getSubject();
            email = oidcUser.getEmail();
            Boolean verified = oidcUser.getEmailVerified();
            emailVerified = verified != null && verified;
        } else {
            // Sin claims OIDC no hay forma de confirmar la verificación del
            // proveedor -> se trata como no verificado (AC #3, mismo criterio
            // que FederatedLoginUseCase aplica al claim email_verified).
            providerUserId = principal.getName();
            email = principal.getAttribute("email");
            emailVerified = false;
        }

        try {
            FederatedLoginCommand command = new FederatedLoginCommand(registrationId, providerUserId, email, emailVerified);
            TokenIssuer.IssuedTokens tokens = federatedLoginUseCase.login(command);
            response.sendRedirect(buildSuccessRedirectUri(tokens));
        } catch (FederatedLoginFailedException failure) {
            failureHandler.onAuthenticationFailure(request, response,
                    new AuthenticationServiceException(failure.getMessage(), failure));
        }
    }

    private String buildSuccessRedirectUri(TokenIssuer.IssuedTokens tokens) {
        OAuth2ExchangeCodeStore.IssuedTokens toStore = new OAuth2ExchangeCodeStore.IssuedTokens(
                tokens.accessToken(), tokens.refreshToken(), tokens.accessTokenExpiresInSeconds());
        String code = exchangeCodeStore.issue(toStore);
        return UriComponentsBuilder.fromUriString(redirectProperties.successRedirectUri())
                .queryParam("code", code)
                .encode()
                .build()
                .toUriString();
    }
}
