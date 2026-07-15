package com.auth_service.auth.infrastructure.adapters.oauth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.Base64;
import java.util.Set;

/**
 * {@code AuthorizationRequestRepository} respaldado por cookie, no por
 * {@code HttpSession} — el servicio es {@code STATELESS} (AD-3) y el flujo
 * estándar de {@code oauth2Login()} de Spring Security guarda el
 * {@link OAuth2AuthorizationRequest} (state + PKCE) en sesión por defecto,
 * incompatible con esa política. Patrón externo bien establecido ("Cookie
 * Authorization Request Repository"), no una invención de esta historia.
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "oauth2_authorization_request";
    private static final int COOKIE_MAX_AGE_SECONDS = 180;

    // El cookie es datos de cliente no confiable: un atacante puede sustituir
    // su valor con bytes arbitrarios. SerializationUtils.deserialize(byte[])
    // (deprecado por Spring) usa un ObjectInputStream sin restricciones, lo
    // que abre la puerta a ataques de deserialización insegura (CWE-502, p. ej.
    // cadenas de gadgets de librerías en el classpath). Se restringe
    // explícitamente el conjunto de clases resolvibles al grafo de objetos
    // exacto de OAuth2AuthorizationRequest (confirmado por reflexión sobre sus
    // campos) — cualquier otra clase en el stream provoca un rechazo.
    private static final Set<String> ALLOWED_CLASSES = Set.of(
            OAuth2AuthorizationRequest.class.getName(),
            AuthorizationGrantType.class.getName(),
            OAuth2AuthorizationResponseType.class.getName(),
            String.class.getName(),
            java.util.LinkedHashMap.class.getName(),
            java.util.LinkedHashSet.class.getName(),
            java.util.HashMap.class.getName(),
            java.util.HashSet.class.getName(),
            "java.util.Collections$UnmodifiableMap",
            "java.util.Collections$UnmodifiableSet",
            "java.util.Collections$UnmodifiableCollection",
            Object.class.getName());

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request).map(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                                          HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeCookie(request, response);
            return;
        }
        Cookie cookie = new Cookie(COOKIE_NAME, serialize(authorizationRequest));
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        removeCookie(request, response);
        return authorizationRequest;
    }

    private void removeCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private java.util.Optional<Cookie> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        return java.util.Arrays.stream(cookies).filter(c -> COOKIE_NAME.equals(c.getName())).findFirst();
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        return Base64.getUrlEncoder().encodeToString(SerializationUtils.serialize(authorizationRequest));
    }

    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cookie.getValue());
            try (ObjectInputStream in = new AllowListObjectInputStream(new ByteArrayInputStream(bytes))) {
                return (OAuth2AuthorizationRequest) in.readObject();
            }
        } catch (IOException | ClassNotFoundException | IllegalArgumentException | ClassCastException malformedOrRejected) {
            // Cookie corrupta, manipulada (Base64 inválido), con una clase
            // fuera de la allow-list, o deserializada a un tipo inesperado —
            // tratarla como ausente en vez de propagar (el caller de Spring
            // Security simplemente reinicia el flujo en vez de recibir un 500).
            return null;
        }
    }

    private static final class AllowListObjectInputStream extends ObjectInputStream {

        AllowListObjectInputStream(ByteArrayInputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            String name = desc.getName();
            // Un nombre de array (p. ej. "[Lcom.evil.Gadget;") pasaba la
            // comprobación con solo el prefijo "[", sin validar el tipo de
            // componente contra la allow-list — reduciendo la protección
            // real de la lista blanca frente a cadenas de gadgets (CWE-502).
            if (name.startsWith("[") && !isAllowedArray(name)) {
                throw new InvalidClassException("Clase no permitida en la deserialización del cookie OAuth2: " + name);
            }
            if (!name.startsWith("[") && !ALLOWED_CLASSES.contains(name)) {
                throw new InvalidClassException("Clase no permitida en la deserialización del cookie OAuth2: " + name);
            }
            return super.resolveClass(desc);
        }

        private boolean isAllowedArray(String arrayClassName) {
            String stripped = arrayClassName.replaceFirst("^\\[+", "");
            if (stripped.length() == 1) {
                // Array de tipo primitivo (p. ej. "[B" para byte[]) — inofensivo,
                // no instancia clases del classpath.
                return true;
            }
            if (stripped.startsWith("L") && stripped.endsWith(";")) {
                return ALLOWED_CLASSES.contains(stripped.substring(1, stripped.length() - 1));
            }
            return false;
        }
    }
}
