package com.auth_service.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FederatedIdentityTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);
    private final AccountId accountId = AccountId.newId();

    @Test
    void linkProducesAnIdentityWithTheGivenProviderAndProviderUserId() {
        FederatedIdentity identity = FederatedIdentity.link(accountId, FederatedProvider.GOOGLE, "google-sub-123", clock);

        assertThat(identity.accountId()).isEqualTo(accountId);
        assertThat(identity.provider()).isEqualTo(FederatedProvider.GOOGLE);
        assertThat(identity.providerUserId()).isEqualTo("google-sub-123");
        assertThat(identity.createdAt()).isEqualTo(Instant.now(clock));
        assertThat(identity.id()).isNotNull();
    }

    @Test
    void twoLinkedIdentitiesHaveDifferentIds() {
        FederatedIdentity first = FederatedIdentity.link(accountId, FederatedProvider.GOOGLE, "sub-a", clock);
        FederatedIdentity second = FederatedIdentity.link(accountId, FederatedProvider.GOOGLE, "sub-b", clock);

        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void reconstituteExposesTheSameFiveFieldsItReceived() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-01T00:00:00Z");

        FederatedIdentity identity = FederatedIdentity.reconstitute(id, accountId, FederatedProvider.GITHUB, "gh-42", createdAt);

        assertThat(identity.id()).isEqualTo(id);
        assertThat(identity.accountId()).isEqualTo(accountId);
        assertThat(identity.provider()).isEqualTo(FederatedProvider.GITHUB);
        assertThat(identity.providerUserId()).isEqualTo("gh-42");
        assertThat(identity.createdAt()).isEqualTo(createdAt);
    }
}
