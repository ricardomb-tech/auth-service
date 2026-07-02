package com.auth_service.auth.infrastructure.adapters.postgresql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Entidad JPA — nunca cruza hacia domain/ (AD-1); {@link VerificationTokenRepositoryAdapter} la mapea. */
@Entity
@Table(name = "verification_tokens")
public class VerificationTokenEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected VerificationTokenEntity() {
        // JPA
    }

    public VerificationTokenEntity(UUID id, UUID accountId, String tokenHash, String purpose,
                                    Instant expiresAt, Instant consumedAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
