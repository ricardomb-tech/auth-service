package com.auth_service.auth.domain.model;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Identidad Federada — vincula una Cuenta a un proveedor externo (Google,
 * GitHub). Agregado propio, no anidado en {@link Account} (mismo criterio que
 * {@link RefreshToken}: la mayoría de los casos de uso no la necesitan en
 * cada lectura de Cuenta). Java puro — sin Spring (AD-1).
 */
public class FederatedIdentity {

    private final UUID id;
    private final AccountId accountId;
    private final FederatedProvider provider;
    private final String providerUserId;
    private final Instant createdAt;

    private FederatedIdentity(UUID id, AccountId accountId, FederatedProvider provider, String providerUserId,
                               Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.createdAt = createdAt;
    }

    /** Vincula una Cuenta a un proveedor externo — nace en el momento del primer login exitoso con ese proveedor. */
    public static FederatedIdentity link(AccountId accountId, FederatedProvider provider, String providerUserId, Clock clock) {
        return new FederatedIdentity(UUID.randomUUID(), accountId, provider, providerUserId, Instant.now(clock));
    }

    /** Reconstruye una Identidad Federada ya persistida — usado únicamente por el adapter de persistencia. */
    public static FederatedIdentity reconstitute(UUID id, AccountId accountId, FederatedProvider provider,
                                                  String providerUserId, Instant createdAt) {
        return new FederatedIdentity(id, accountId, provider, providerUserId, createdAt);
    }

    public UUID id() {
        return id;
    }

    public AccountId accountId() {
        return accountId;
    }

    public FederatedProvider provider() {
        return provider;
    }

    public String providerUserId() {
        return providerUserId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
