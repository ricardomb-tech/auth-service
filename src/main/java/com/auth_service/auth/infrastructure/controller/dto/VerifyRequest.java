package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(@NotBlank(message = "El token es obligatorio.") String token) {
}
