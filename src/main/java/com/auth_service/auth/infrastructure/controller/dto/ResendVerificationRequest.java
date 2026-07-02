package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "El email es obligatorio.")
        @Size(max = 254, message = "El email es demasiado largo.") String email) {
}
