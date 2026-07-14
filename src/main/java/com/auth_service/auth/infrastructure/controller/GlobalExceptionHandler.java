package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.exception.InvalidRefreshTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce excepciones de dominio/aplicación a Problem Details (RFC 7807,
 * AD-8). Extiende {@link ResponseEntityExceptionHandler} para heredar el
 * manejo estándar de Spring de {@code MethodArgumentNotValidException}
 * (fallos de {@code @Valid}), que ya devuelve {@link ProblemDetail}.
 *
 * <p>No cubre excepciones lanzadas por el filtro de seguridad
 * (401/403) — esas las maneja {@code SecurityConfig} directamente, ver el
 * "Contrato a mantener sincronizado" documentado en su Javadoc (Story 1.1).</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainValidationException.class)
    public ProblemDetail handleDomainValidationException(DomainValidationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Mensaje fijo, no {@code ex.getMessage()} — las tres causas de rechazo
     * (email inexistente, password incorrecta, Cuenta no ACTIVE) comparten
     * esta misma excepción precisamente para no filtrar el motivo real
     * (NFR-2, AC #2/#3 de la Story 1.4).
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ProblemDetail handleAuthenticationFailedException(AuthenticationFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Email o contraseña incorrectos.");
    }

    /**
     * Mensaje fijo, no {@code ex.getMessage()} — no reconocido, expirado,
     * revocado y reuso detectado comparten esta misma excepción precisamente
     * para no filtrar el motivo real (AC #2/#3 de la Story 1.5, AD-8).
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshTokenException(InvalidRefreshTokenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Refresh token inválido o expirado.");
    }

    /**
     * Mensaje idéntico al de {@code SecurityConfig.authenticationEntryPoint()}
     * (Story 1.1) — un {@code sub} de token válido que ya no corresponde a
     * ninguna Cuenta (Story 1.7, AC #3) debe ser indistinguible de un token
     * ausente desde la perspectiva del cliente. El motivo real sí se loguea
     * para el operador: un token con firma válida rechazado aquí es síntoma
     * de una Cuenta borrada fuera de la API o de un bug del emisor.
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFoundException(AccountNotFoundException ex) {
        log.warn("Access Token con firma válida rechazado: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    /**
     * Red de seguridad: cualquier excepción no mapeada explícitamente termina
     * aquí en vez de en el manejo por defecto de Spring — nunca se expone el
     * detalle interno al cliente (AD-8).
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex) {
        log.error("Excepción no controlada", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Ha ocurrido un error inesperado.");
    }
}
