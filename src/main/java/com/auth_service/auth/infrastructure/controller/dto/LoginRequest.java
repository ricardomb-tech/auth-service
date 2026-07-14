package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sin validación de formato de password (a diferencia de {@link RegisterRequest}):
 * una contraseña que no cumple la política igual debe compararse contra el
 * hash y fallar como credencial inválida genérica, no como 400 de formato
 * (NFR-2, Story 1.4). El largo máximo sí se acota (defensa en profundidad
 * antes de invocar al dominio, mismo límite que {@link RegisterRequest}) —
 * eso no es política de contraseña, es un guard de tamaño de entrada.
 */
public record LoginRequest(
        @NotBlank(message = "El email es obligatorio.") String email,
        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(max = 72, message = "La contraseña no puede superar los 72 caracteres.") String password) {
}
