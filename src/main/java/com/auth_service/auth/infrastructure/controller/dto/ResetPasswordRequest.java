package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solo valida presencia y un largo máximo razonable (defensa en profundidad
 * antes de invocar al dominio) — mismo patrón que {@code RegisterRequest}.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "El token es obligatorio.")
        @Size(max = 512, message = "El token es demasiado largo.") String token,
        @NotBlank(message = "La nueva contraseña es obligatoria.")
        @Size(max = 72, message = "La contraseña no puede superar los 72 caracteres.") String newPassword) {
}
