package com.auth_service.auth.infrastructure.adapters.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub no es un proveedor OIDC (a diferencia de Google, Story 2.1) — su
 * recurso {@code GET /user} no expone si el email es verificado. La única
 * fuente confiable es {@code GET /user/emails} (requiere scope
 * {@code user:email}, ver {@code application.properties}). Este servicio
 * fusiona el email primario verificado encontrado ahí como los atributos
 * sintéticos {@code email}/{@code email_verified}, mismo par de claims que
 * {@link OAuth2AuthenticationSuccessHandler} ya lee del {@code OidcUser} de
 * Google — así ese handler no necesita bifurcar lógica de negocio por
 * proveedor (Story 2.2).
 */
@Component
public class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuth2UserService.class);

    private static final String GITHUB_REGISTRATION_ID = "github";
    private static final String GITHUB_EMAILS_URI = "https://api.github.com/user/emails";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final RestClient restClient;

    public GitHubOAuth2UserService() {
        this(new DefaultOAuth2UserService(), RestClient.builder().requestFactory(timeoutRequestFactory()).build());
    }

    GitHubOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate, RestClient restClient) {
        this.delegate = delegate;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User baseUser = delegate.loadUser(userRequest);
        if (!GITHUB_REGISTRATION_ID.equals(userRequest.getClientRegistration().getRegistrationId())) {
            return baseUser;
        }

        String verifiedEmail = fetchPrimaryVerifiedEmail(userRequest.getAccessToken().getTokenValue());

        Map<String, Object> attributes = new HashMap<>(baseUser.getAttributes());
        attributes.put("email", verifiedEmail);
        attributes.put("email_verified", verifiedEmail != null);

        return new DefaultOAuth2User(baseUser.getAuthorities(), attributes, "id");
    }

    private String fetchPrimaryVerifiedEmail(String accessToken) {
        try {
            List<Map<String, Object>> emails = restClient.get()
                    .uri(GITHUB_EMAILS_URI)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            if (emails == null) {
                return null;
            }
            return emails.stream()
                    .filter(email -> Boolean.TRUE.equals(email.get("primary")) && Boolean.TRUE.equals(email.get("verified")))
                    .map(email -> (String) email.get("email"))
                    .findFirst()
                    .orElse(null);
        } catch (RestClientException | ClassCastException | NullPointerException failure) {
            // Timeout, DNS, 4xx/5xx de GitHub, o JSON con forma inesperada
            // (elemento null, "email" no-String) — nunca debe escapar como
            // excepción cruda (AC #4). OAuth2AuthenticationException es
            // capturada internamente por OAuth2LoginAuthenticationFilter y
            // enrutada al AuthenticationFailureHandler configurado (mecanismo
            // estándar de Spring Security, no una invención de esta historia).
            log.warn("No se pudo resolver el email verificado de GitHub: {}", failure.getMessage());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("github_email_lookup_failed", "No se pudo verificar el email de GitHub.", null),
                    failure);
        }
    }
}
