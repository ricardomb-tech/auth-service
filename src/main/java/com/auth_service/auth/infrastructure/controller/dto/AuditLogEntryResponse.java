package com.auth_service.auth.infrastructure.controller.dto;

import java.time.Instant;

public record AuditLogEntryResponse(String id, String actorAccountId, String targetAccountId,
                                     String action, String result, Instant occurredAt) {
}
