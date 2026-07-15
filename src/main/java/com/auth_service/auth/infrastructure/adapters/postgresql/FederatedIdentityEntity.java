package com.auth_service.auth.infrastructure.adapters.postgresql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Entidad JPA — nunca cruza hacia domain/ (AD-1); {@link FederatedIdentityRepositoryAdapter} la mapea. */
@Entity
@Table(name = "federated_identities")
public class FederatedIdentityEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FederatedIdentityEntity() {
        // JPA
    }

    public FederatedIdentityEntity(UUID id, UUID accountId, String provider, String providerUserId, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
