package com.auth_service.auth.infrastructure.adapters.postgresql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Entidad JPA — nunca cruza hacia domain/ (AD-1); {@link AuditLogRepositoryAdapter} la mapea. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    private UUID id;

    @Column(name = "actor_account_id", nullable = false)
    private UUID actorAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String result;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntity() {
        // JPA
    }

    public AuditLogEntity(UUID id, UUID actorAccountId, UUID targetAccountId, String action, String result,
                           Instant occurredAt) {
        this.id = id;
        this.actorAccountId = actorAccountId;
        this.targetAccountId = targetAccountId;
        this.action = action;
        this.result = result;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorAccountId() {
        return actorAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public String getAction() {
        return action;
    }

    public String getResult() {
        return result;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
