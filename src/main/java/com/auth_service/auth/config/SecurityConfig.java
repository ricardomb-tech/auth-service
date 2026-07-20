package com.auth_service.auth.config;

import com.auth_service.auth.infrastructure.adapters.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.auth_service.auth.infrastructure.adapters.oauth.GitHubOAuth2UserService;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationFailureHandler;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationSuccessHandler;
import com.auth_service.auth.infrastructure.adapters.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Deny-all por defecto (AD-11): toda ruta nace protegida; lo público se lista
 * explícitamente. {@code /oauth2/**} (inicia el flujo) y {@code /login/oauth2/**}
 * (callback del proveedor) son públicos desde la Story 2.1 — el propio
 * {@code oauth2Login()} de Spring Security exige que lo sean, la
 * autenticación ocurre dentro de ese mismo flujo.
 *
 * <p>Las excepciones de autenticación/autorización se manejan a nivel del
 * filtro de seguridad (no llegan a un {@code @RestControllerAdvice} de
 * Spring MVC porque ocurren antes del dispatcher), por eso se configuran
 * aquí un {@link AuthenticationEntryPoint} y {@link AccessDeniedHandler}
 * que escriben directamente un cuerpo Problem Details — AD-8.</p>
 *
 * <p><b>Contrato a mantener sincronizado:</b> cuando una historia futura
 * añada un {@code GlobalExceptionHandler} ({@code @RestControllerAdvice})
 * para las excepciones de dominio/aplicación, su forma de construir el
 * {@link ProblemDetail} (campos presentes, formato de {@code title}) debe
 * coincidir con {@link #writeProblemDetail} — son dos caminos de error
 * distintos que un consumidor de la API debe poder tratar como uno solo.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/error",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/auth/**",
            "/oauth2/**",
            "/login/oauth2/**"
    };

    private final ObjectMapper objectMapper;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final GitHubOAuth2UserService gitHubOAuth2UserService;

    public SecurityConfig(ObjectMapper objectMapper, JwtAuthenticationFilter jwtAuthenticationFilter,
                           CookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository,
                           OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
                           OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler,
                           GitHubOAuth2UserService gitHubOAuth2UserService) {
        this.objectMapper = objectMapper;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.cookieAuthorizationRequestRepository = cookieAuthorizationRequestRepository;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.oAuth2AuthenticationFailureHandler = oAuth2AuthenticationFailureHandler;
        this.gitHubOAuth2UserService = gitHubOAuth2UserService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Story 2.1 (FR-6) — el AuthorizationRequestRepository respaldado
                // por cookie (no sesión) mantiene el flujo compatible con
                // STATELESS (AD-3); los handlers delegan en FederatedLoginUseCase.
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(cookieAuthorizationRequestRepository))
                        // Story 2.2 — solo afecta el flujo no-OIDC (GitHub); el
                        // flujo OIDC de Google (oidcUserService, no tocado) sigue
                        // exactamente igual.
                        .userInfoEndpoint(endpoint -> endpoint.userService(gitHubOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler));

        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
                writeProblemDetail(response, HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeProblemDetail(response, HttpStatus.FORBIDDEN, "Access denied.");
    }

    private void writeProblemDetail(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problemDetail));
    }
}
