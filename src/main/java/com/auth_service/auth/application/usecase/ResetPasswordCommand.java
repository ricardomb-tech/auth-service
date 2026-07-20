package com.auth_service.auth.application.usecase;

public record ResetPasswordCommand(String rawToken, String newRawPassword) {
}
