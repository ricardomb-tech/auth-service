package com.auth_service.auth.application.usecase.command;

public record ResetPasswordCommand(String rawToken, String newRawPassword) {
}
