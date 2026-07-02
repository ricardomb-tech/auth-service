package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Solo valida presencia y un largo máximo razonable (defensa en profundidad
 * antes de invocar al dominio) — el formato de email y la política de
 * contraseña se validan en el dominio ({@code Email}/{@code RawPassword}, AD-14).
 */
public record RegisterRequest(
        @NotBlank(message = "El email es obligatorio.")
        @Size(max = 254, message = "El email es demasiado largo.") String email,
        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(max = 72, message = "La contraseña no puede superar los 72 caracteres.") String password) {
}
