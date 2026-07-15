package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.application.usecase.FederatedLoginCommand;
import com.auth_service.auth.application.usecase.FederatedLoginUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.config.OAuth2RedirectProperties;
import com.auth_service.auth.domain.exception.FederatedLoginFailedException;
import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    private final FederatedLoginUseCase federatedLoginUseCase = mock(FederatedLoginUseCase.class);
    private final OAuth2ExchangeCodeStore exchangeCodeStore = mock(OAuth2ExchangeCodeStore.class);
    private final OAuth2RedirectProperties redirectProperties =
            new OAuth2RedirectProperties("https://frontend.example.com/success", "https://frontend.example.com/failure");
    private final AuthenticationFailureHandler failureHandler = mock(AuthenticationFailureHandler.class);
    private final OAuth2AuthenticationSuccessHandler handler =
            new OAuth2AuthenticationSuccessHandler(federatedLoginUseCase, exchangeCodeStore, redirectProperties, failureHandler);

    private OidcUser oidcUser(String subject, String email, boolean emailVerified) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("sub", subject)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    private OAuth2AuthenticationToken tokenFor(OidcUser user) {
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }

    @Test
    void successfulLoginInvokesUseCaseWithExtractedClaimsAndRedirectsWithExchangeCode() throws Exception {
        OidcUser user = oidcUser("google-sub-1", "titular@example.com", true);
        OAuth2AuthenticationToken authentication = tokenFor(user);
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access-abc", "refresh-xyz", 900L);
        when(federatedLoginUseCase.login(any())).thenReturn(issuedTokens);
        when(exchangeCodeStore.issue(any())).thenReturn("exchange-code-123");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedLoginUseCase).login(eq(new FederatedLoginCommand("google", "google-sub-1", "titular@example.com", true)));
        verify(exchangeCodeStore).issue(eq(new OAuth2ExchangeCodeStore.IssuedTokens("access-abc", "refresh-xyz", 900L)));
        verify(response).sendRedirect(contains("https://frontend.example.com/success"));
        verify(response).sendRedirect(contains("code=exchange-code-123"));
        verify(response, never()).sendRedirect(contains("accessToken="));
        verify(response, never()).sendRedirect(contains("refreshToken="));
        verify(failureHandler, never()).onAuthenticationFailure(any(), any(), any());
    }

    @Test
    void unverifiedEmailStillReachesUseCaseWithEmailVerifiedFalse() throws Exception {
        OidcUser user = oidcUser("google-sub-2", "sinverificar@example.com", false);
        OAuth2AuthenticationToken authentication = tokenFor(user);
        when(federatedLoginUseCase.login(any())).thenThrow(new FederatedLoginFailedException("Email no verificado por el proveedor."));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(federatedLoginUseCase).login(eq(new FederatedLoginCommand("google", "google-sub-2", "sinverificar@example.com", false)));
        verify(failureHandler).onAuthenticationFailure(eq(request), eq(response), any(AuthenticationServiceException.class));
        verify(response, never()).sendRedirect(any());
    }

    @Test
    void useCaseFailureDelegatesToFailureHandlerInsteadOfPropagating() throws Exception {
        OidcUser user = oidcUser("google-sub-3", "titular@example.com", true);
        OAuth2AuthenticationToken authentication = tokenFor(user);
        when(federatedLoginUseCase.login(any())).thenThrow(new FederatedLoginFailedException("Proveedor de login federado no reconocido."));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(failureHandler).onAuthenticationFailure(eq(request), eq(response), any(AuthenticationServiceException.class));
        verify(response, never()).sendRedirect(any());
    }
}
