package com.auth_service.auth.domain.exception;

/**
 * Fallo controlado del login federado (Story 2.1, AC #3) — proveedor no
 * reconocido, email no verificado por el proveedor, o email con formato
 * inválido. Mapea a un error controlado, nunca a un {@code 500}. No reutiliza
 * {@link AuthenticationFailedException}: su mensaje está fijado al dominio de
 * error de login con credenciales (Story 1.4), semánticamente distinto de
 * este caso (no hubo contraseña). Java puro (AD-1).
 */
public class FederatedLoginFailedException extends RuntimeException {

    public FederatedLoginFailedException(String message) {
        super(message);
    }
}
