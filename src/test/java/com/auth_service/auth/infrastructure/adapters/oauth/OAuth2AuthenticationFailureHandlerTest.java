package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.config.OAuth2RedirectProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OAuth2AuthenticationFailureHandlerTest {

    private final OAuth2RedirectProperties redirectProperties =
            new OAuth2RedirectProperties("https://frontend.example.com/success", "https://frontend.example.com/failure");
    private final OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(redirectProperties);

    @Test
    void redirectsToFailureUriWithGenericErrorCodeNeverExposingExceptionDetail() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        String sensitiveDetail = "detalle interno sensible que no debe llegar al cliente";

        handler.onAuthenticationFailure(request, response, new AuthenticationServiceException(sensitiveDetail));

        var captor = forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        assertThat(captor.getValue()).startsWith("https://frontend.example.com/failure");
        assertThat(captor.getValue()).contains("error=federated_login_failed");
        assertThat(captor.getValue()).doesNotContain("sensible");
    }
}
