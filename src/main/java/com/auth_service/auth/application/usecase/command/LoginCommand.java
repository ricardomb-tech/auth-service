package com.auth_service.auth.application.usecase.command;

public record LoginCommand(String rawEmail, String rawPassword) {
}
