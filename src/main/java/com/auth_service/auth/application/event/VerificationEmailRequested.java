package com.auth_service.auth.application.event;

import com.auth_service.auth.domain.model.Email;

/**
 * Señal interna de Spring para diferir el envío del email de verificación
 * hasta después del commit (AD-9). NO es un Evento de Dominio (AD-15,
 * {@code domain/event}) — no participa del patrón Transactional Outbox ni de
 * la integración con Servicios Consumidores; eso lo añade la Story 6.1.
 */
public record VerificationEmailRequested(Email recipient, String rawToken) {
}
