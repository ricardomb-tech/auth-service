package com.auth_service.auth.config;

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

import java.io.IOException;

/**
 * Deny-all por defecto (AD-11): toda ruta nace protegida; lo público se lista
 * explícitamente. Las historias que introducen /auth/** y /oauth2/** amplían
 * esta lista de rutas públicas — no se anticipan aquí.
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
            "/auth/**"
    };

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                        .accessDeniedHandler(accessDeniedHandler()));

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
