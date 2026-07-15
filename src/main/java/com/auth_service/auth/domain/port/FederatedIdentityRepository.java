package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.FederatedIdentity;
import com.auth_service.auth.domain.model.FederatedProvider;

import java.util.List;
import java.util.Optional;

public interface FederatedIdentityRepository {

    FederatedIdentity save(FederatedIdentity federatedIdentity);

    Optional<FederatedIdentity> findByProviderAndProviderUserId(FederatedProvider provider, String providerUserId);

    List<FederatedIdentity> findByAccountId(AccountId accountId);
}
