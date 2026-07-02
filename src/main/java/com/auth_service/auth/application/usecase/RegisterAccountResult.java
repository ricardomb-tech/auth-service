package com.auth_service.auth.application.usecase;

/**
 * Uso interno (logging/tests) — el controller NUNCA expone cuál de estos dos
 * valores ocurrió en la respuesta HTTP (anti-enumeración, AC #2 de la Story 1.2).
 */
public enum RegisterAccountResult {
    ACCOUNT_CREATED,
    EMAIL_ALREADY_REGISTERED
}
