package com.auth_service.auth.infrastructure.adapters.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookieOAuth2AuthorizationRequestRepositoryTest {

    private final CookieOAuth2AuthorizationRequestRepository repository = new CookieOAuth2AuthorizationRequestRepository();

    private OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-id")
                .redirectUri("https://auth.example.com/login/oauth2/code/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state("state-123")
                .additionalParameters(Map.of("registration_id", "google"))
                .build();
    }

    @Test
    void loadAuthorizationRequestReturnsNullWhenNoCookiePresent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    @Test
    void savedRequestCanBeLoadedBackFromTheCookieItWrote() {
        HttpServletRequest saveRequest = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(saveRequest.isSecure()).thenReturn(true);
        OAuth2AuthorizationRequest original = sampleRequest();

        repository.saveAuthorizationRequest(original, saveRequest, response);

        var captor = forClass(Cookie.class);
        verify(response).addCookie(captor.capture());
        Cookie savedCookie = captor.getValue();
        assertThat(savedCookie.getName()).isEqualTo("oauth2_authorization_request");
        assertThat(savedCookie.isHttpOnly()).isTrue();
        assertThat(savedCookie.getSecure()).isTrue();
        assertThat(savedCookie.getMaxAge()).isEqualTo(180);

        HttpServletRequest loadRequest = mock(HttpServletRequest.class);
        when(loadRequest.getCookies()).thenReturn(new Cookie[]{savedCookie});

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(loadRequest);

        assertThat(loaded.getState()).isEqualTo("state-123");
        assertThat(loaded.getClientId()).isEqualTo("client-id");
        assertThat(loaded.getAuthorizationRequestUri()).contains("accounts.google.com");
    }

    @Test
    void removeAuthorizationRequestReturnsTheStoredRequestAndExpiresTheCookie() {
        HttpServletRequest saveRequest = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(saveRequest.isSecure()).thenReturn(false);
        repository.saveAuthorizationRequest(sampleRequest(), saveRequest, response);
        var captor = forClass(Cookie.class);
        verify(response).addCookie(captor.capture());
        Cookie savedCookie = captor.getValue();

        HttpServletRequest removeRequest = mock(HttpServletRequest.class);
        HttpServletResponse removeResponse = mock(HttpServletResponse.class);
        when(removeRequest.getCookies()).thenReturn(new Cookie[]{savedCookie});
        when(removeRequest.isSecure()).thenReturn(false);

        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(removeRequest, removeResponse);

        assertThat(removed.getState()).isEqualTo("state-123");
        var removeCaptor = forClass(Cookie.class);
        verify(removeResponse).addCookie(removeCaptor.capture());
        assertThat(removeCaptor.getValue().getMaxAge()).isZero();
    }

    @Test
    void malformedCookieValueIsTreatedAsAbsentRatherThanThrowing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("oauth2_authorization_request", "not-valid-base64-or-serialized-data")});

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    @Test
    void cookieCarryingADisallowedClassIsRejectedRatherThanDeserialized() throws Exception {
        // Simula un atacante sustituyendo el valor del cookie por bytes
        // serializados de una clase fuera de la allow-list (aquí, un simple
        // ArrayList en vez de OAuth2AuthorizationRequest) — debe rechazarse,
        // nunca deserializarse silenciosamente.
        var bytes = org.springframework.util.SerializationUtils.serialize(new java.util.ArrayList<>(java.util.List.of("payload")));
        String tamperedValue = java.util.Base64.getUrlEncoder().encodeToString(bytes);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("oauth2_authorization_request", tamperedValue)});

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    @Test
    void savingNullAuthorizationRequestExpiresTheCookieInstead() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getCookies()).thenReturn(null);
        when(request.isSecure()).thenReturn(false);

        repository.saveAuthorizationRequest(null, request, response);

        var captor = forClass(Cookie.class);
        verify(response).addCookie(captor.capture());
        assertThat(captor.getValue().getMaxAge()).isZero();
    }
}
