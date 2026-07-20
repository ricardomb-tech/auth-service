package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "El token es obligatorio.") String token,
        @NotBlank(message = "La nueva contraseña es obligatoria.") String newPassword) {
}
