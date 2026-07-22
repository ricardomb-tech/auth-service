package com.auth_service.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Fila de auditoría inmutable (FR-13, AD-20) — un hecho ya ocurrido, nunca se edita tras crearse. */
public record AuditLogEntry(UUID id, AccountId actorAccountId, AccountId targetAccountId, AdminAction action,
                             AuditResult result, Instant occurredAt) {

    public static AuditLogEntry create(AccountId actorAccountId, AccountId targetAccountId, AdminAction action,
                                        AuditResult result, Instant occurredAt) {
        return new AuditLogEntry(UUID.randomUUID(), actorAccountId, targetAccountId, action, result, occurredAt);
    }
}
