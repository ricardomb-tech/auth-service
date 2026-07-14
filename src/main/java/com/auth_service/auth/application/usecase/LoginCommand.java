package com.auth_service.auth.application.usecase;

public record LoginCommand(String rawEmail, String rawPassword) {
}
