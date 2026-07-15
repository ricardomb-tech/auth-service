package com.auth_service.auth.infrastructure.adapters.postgresql;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FederatedIdentityJpaRepository extends JpaRepository<FederatedIdentityEntity, UUID> {

    Optional<FederatedIdentityEntity> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<FederatedIdentityEntity> findByAccountId(UUID accountId);
}
