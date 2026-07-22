package com.auth_service.auth.application.usecase.command;

public record FederatedLoginCommand(String provider, String providerUserId, String rawEmail, boolean emailVerified) {
}
