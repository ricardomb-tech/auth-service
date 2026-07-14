package com.auth_service.auth.infrastructure.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ProfileResponse(String id, String email, Set<String> roles, String status,
                               List<String> federatedIdentities, Instant createdAt) {
}
