package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.exception.InvalidRefreshTokenException;
import com.auth_service.auth.domain.exception.OAuth2ExchangeFailedException;
import com.auth_service.auth.domain.exception.SelfManagementNotAllowedException;
import com.auth_service.auth.domain.exception.TargetAccountNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce excepciones de dominio/aplicación a Problem Details (RFC 7807,
 * AD-8). Extiende {@link ResponseEntityExceptionHandler} para heredar el
 * manejo estándar de Spring de {@code MethodArgumentNotValidException}
 * (fallos de {@code @Valid}), que ya devuelve {@link ProblemDetail}.
 *
 * <p>No cubre las excepciones 401 lanzadas por el filtro de seguridad
 * antes del dispatcher — esas las maneja {@code SecurityConfig} directamente
 * (ver su "Contrato a mantener sincronizado", Story 1.1). Pero SÍ cubre
 * {@link AccessDeniedException} (Story 4.2, {@code @PreAuthorize}): a
 * diferencia del deny-all de nivel de filtro, la autorización por método
 * ocurre DENTRO del dispatch de Spring MVC (proxy AOP del controller), así
 * que la excepción nunca llega a {@code ExceptionTranslationFilter} — el
 * catch-all de más abajo la interceptaría primero y la convertiría en un 500
 * genérico si no se mapeara aquí explícitamente a 403.</p>
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
     * Mensaje fijo, no {@code ex.getMessage()} — código no reconocido, ya
     * usado o expirado comparten esta misma excepción precisamente para no
     * filtrar el motivo real (AD-8), mismo principio que {@link
     * #handleInvalidRefreshTokenException}.
     */
    @ExceptionHandler(OAuth2ExchangeFailedException.class)
    public ProblemDetail handleOAuth2ExchangeFailedException(OAuth2ExchangeFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Código de intercambio inválido o expirado.");
    }

    /**
     * FR-11 (Story 4.2) — el Administrador consultó/mutó un id de Cuenta
     * ajena que no existe. Distinta de {@link AccountNotFoundException}
     * (401, "mi propio Access Token ya no corresponde a ninguna Cuenta") —
     * reutilizar esa produciría un 401 semánticamente incorrecto aquí.
     */
    @ExceptionHandler(TargetAccountNotFoundException.class)
    public ProblemDetail handleTargetAccountNotFoundException(TargetAccountNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * FR-11 (Story 4.2) — auto-protección: un Administrador no puede
     * desactivarse ni quitarse el Rol ADMIN a sí mismo. {@code
     * ex.getMessage()} sí se expone aquí — quien recibe esta respuesta ya es
     * un ADMIN autenticado, no un visitante anónimo, así que no hay riesgo
     * de enumeración (a diferencia de {@code AuthenticationFailedException}).
     */
    @ExceptionHandler(SelfManagementNotAllowedException.class)
    public ProblemDetail handleSelfManagementNotAllowedException(SelfManagementNotAllowedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * AD-11 (Story 4.2) — mismo mensaje fijo que {@code SecurityConfig.accessDeniedHandler()}
     * ("Access denied.") para que ambos caminos de 403 (deny-all de filtro vs.
     * {@code @PreAuthorize} de método) sean indistinguibles para el cliente.
     * Cubre también {@code AuthorizationDeniedException} (Spring Security
     * 6.3+), que extiende {@link AccessDeniedException}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied.");
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
