package com.auth_service.auth.application.usecase;

/**
 * Uso interno (logging/tests) — el controller NUNCA expone cuál de estos dos
 * valores ocurrió en la respuesta HTTP (anti-enumeración, AC #4 de la Story 1.3).
 */
public enum ResendVerificationResult {
    RESENT,
    NOT_APPLICABLE
}
