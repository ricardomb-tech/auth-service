package com.auth_service.auth.infrastructure.controller.dto;

public record LoginResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {
}
