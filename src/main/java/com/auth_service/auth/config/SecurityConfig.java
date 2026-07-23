package com.auth_service.auth.config;

import com.auth_service.auth.infrastructure.adapters.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.auth_service.auth.infrastructure.adapters.oauth.GitHubOAuth2UserService;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationFailureHandler;
import com.auth_service.auth.infrastructure.adapters.oauth.OAuth2AuthenticationSuccessHandler;
import com.auth_service.auth.infrastructure.adapters.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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
@EnableMethodSecurity
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

    /**
     * Cadena de seguridad dedicada al puerto de management (Story 5.1,
     * AD-16). Cuando {@code management.server.port} difiere de
     * {@code server.port}, Spring Boot arranca un contexto hijo para
     * Actuator que sigue viendo los beans {@link SecurityFilterChain} del
     * contexto padre — sin esta cadena adicional, el único
     * {@code SecurityFilterChain} existente (el de negocio, deny-all) pasa
     * a aplicarse también a las peticiones del puerto de management,
     * devolviendo 401. {@code @Order(HIGHEST_PRECEDENCE)} le da prioridad
     * de coincidencia sobre {@link #securityFilterChain}: cualquier ruta
     * bajo {@code /actuator/**} usa esta cadena (permitAll) en vez de la de
     * negocio, sin tocar {@link #PUBLIC_ENDPOINTS} — AD-11 exige
     * explícitamente que {@code /actuator/**} nunca aparezca en esa lista;
     * el aislamiento es por puerto físico, no por regla de autorización de
     * negocio. Una petición a {@code /actuator/**} contra el puerto de
     * negocio pasa esta cadena (permitAll) pero no encuentra ningún
     * handler ahí (los endpoints de Actuator solo están mapeados en el
     * contexto hijo del puerto de management) y recibe 404, no 401.
     *
     * <p><b>{@code securityMatcher("/actuator/**")} en vez de
     * {@code EndpointRequest.toAnyEndpoint()}:</b> ese matcher de
     * conveniencia de Actuator resuelve el conjunto de endpoints contra el
     * {@code PathMappedEndpoints} del contexto donde se evalúa la petición
     * — en pruebas con contexto hijo de management, esa resolución fue
     * inconsistente entre endpoints (funcionó para {@code health/*},
     * falló para {@code prometheus}). Un matcher de ruta literal no
     * depende de esa introspección y cubre todo {@code /actuator/**} de
     * forma uniforme.</p>
     *
     * <p><b>{@link AntPathRequestMatcher} explícito, no el overload
     * {@code securityMatcher(String...)}:</b> ese overload intenta resolver
     * un {@code MvcRequestMatcher}, que requiere el bean
     * {@code mvcHandlerMappingIntrospector} — ausente en cualquier test que
     * arranque el contexto con {@code spring.main.web-application-type=none}
     * (p. ej. {@code RefreshTokenRepositoryAdapterTest}), rompiendo la carga
     * del contexto para TODA la suite, no solo los tests de Actuator.
     * {@code AntPathRequestMatcher} es un matcher de ruta plano, sin
     * dependencia de infraestructura de Spring MVC.</p>
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(new AntPathRequestMatcher("/actuator/**"))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
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
