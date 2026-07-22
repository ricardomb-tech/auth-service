package com.auth_service.auth.infrastructure.controller.dto;

import java.time.Instant;
import java.util.Set;

public record AdminAccountResponse(String id, String email, Set<String> roles, String status, Instant createdAt) {
}
