package com.auth_service.auth.infrastructure.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuth2ExchangeRequest(
        @NotBlank(message = "El código de intercambio es obligatorio.") String code) {
}
