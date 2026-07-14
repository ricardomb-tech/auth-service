package com.auth_service.auth.domain.exception;

/**
 * El {@code sub} de un Access Token válido no corresponde a ninguna Cuenta
 * existente — mapea a 401, no a 500 (AC #3 de la Story 1.7). No reutiliza
 * {@link AuthenticationFailedException}: su mensaje está fijado al dominio
 * de error de login (Story 1.4), semánticamente distinto de este caso. Java
 * puro (AD-1).
 *
 * <p><strong>Acoplamiento a mantener presente:</strong> {@code GlobalExceptionHandler}
 * mapea esta excepción globalmente a {@code 401 "Authentication required."} —
 * semántica de "la Cuenta del propio token autenticado no existe". Un flujo
 * futuro que busque una Cuenta ajena (p. ej. {@code GET /users/{id}} admin,
 * Epic 4) NO debe reutilizarla: ese caso es un {@code 404}, no un {@code 401},
 * y necesita su propia excepción.</p>
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
