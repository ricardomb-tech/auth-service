package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.FederatedIdentity;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.port.FederatedIdentityRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class FederatedIdentityRepositoryAdapter implements FederatedIdentityRepository {

    private final FederatedIdentityJpaRepository jpaRepository;

    public FederatedIdentityRepositoryAdapter(FederatedIdentityJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public FederatedIdentity save(FederatedIdentity federatedIdentity) {
        FederatedIdentityEntity entity = new FederatedIdentityEntity(
                federatedIdentity.id(),
                federatedIdentity.accountId().value(),
                federatedIdentity.provider().name(),
                federatedIdentity.providerUserId(),
                federatedIdentity.createdAt());
        jpaRepository.save(entity);
        return federatedIdentity;
    }

    @Override
    public Optional<FederatedIdentity> findByProviderAndProviderUserId(FederatedProvider provider, String providerUserId) {
        return jpaRepository.findByProviderAndProviderUserId(provider.name(), providerUserId).map(this::toDomain);
    }

    @Override
    public List<FederatedIdentity> findByAccountId(AccountId accountId) {
        return jpaRepository.findByAccountId(accountId.value()).stream().map(this::toDomain).toList();
    }

    private FederatedIdentity toDomain(FederatedIdentityEntity entity) {
        return FederatedIdentity.reconstitute(
                entity.getId(),
                new AccountId(entity.getAccountId()),
                FederatedProvider.valueOf(entity.getProvider()),
                entity.getProviderUserId(),
                entity.getCreatedAt());
    }
}
