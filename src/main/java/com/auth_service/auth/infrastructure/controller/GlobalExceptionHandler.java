package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.exception.DomainValidationException;
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
