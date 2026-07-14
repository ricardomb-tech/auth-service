package com.auth_service.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
    private final AccountId accountId = AccountId.newId();

    @Test
    void issueProducesAFreshUnusedUnrevokedToken() {
        UUID familyId = UUID.randomUUID();

        RefreshToken.Issued issued = RefreshToken.issue(accountId, familyId, Duration.ofDays(7), clock);

        assertThat(issued.token().usedAt()).isNull();
        assertThat(issued.token().revokedAt()).isNull();
        assertThat(issued.token().familyId()).isEqualTo(familyId);
        assertThat(issued.token().accountId()).isEqualTo(accountId);
        assertThat(issued.token().expiresAt()).isEqualTo(Instant.now(clock).plus(Duration.ofDays(7)));
    }

    @Test
    void rawTokenIsNeverEqualToItsHash() {
        RefreshToken.Issued issued = RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ofDays(7), clock);

        assertThat(issued.rawToken()).isNotEqualTo(issued.token().tokenHash());
    }

    @Test
    void twoIssuedTokensHaveDifferentRawValuesAndHashes() {
        RefreshToken.Issued first = RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ofDays(7), clock);
        RefreshToken.Issued second = RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ofDays(7), clock);

        assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
        assertThat(first.token().tokenHash()).isNotEqualTo(second.token().tokenHash());
    }

    @Test
    void hashRawTokenIsDeterministicAndMatchesTheHashStoredAtIssuance() {
        RefreshToken.Issued issued = RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ofDays(7), clock);

        assertThat(RefreshToken.hashRawToken(issued.rawToken())).isEqualTo(issued.token().tokenHash());
        assertThat(RefreshToken.hashRawToken(issued.rawToken())).isEqualTo(RefreshToken.hashRawToken(issued.rawToken()));
    }

    @Test
    void reconstituteExposesTheSameSevenFieldsItReceived() {
        UUID id = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-07-17T00:00:00Z");
        Instant usedAt = Instant.parse("2026-07-11T01:00:00Z");
        Instant revokedAt = Instant.parse("2026-07-11T02:00:00Z");

        RefreshToken token = RefreshToken.reconstitute(id, accountId, "a-hash", familyId, expiresAt, usedAt, revokedAt);

        assertThat(token.id()).isEqualTo(id);
        assertThat(token.accountId()).isEqualTo(accountId);
        assertThat(token.tokenHash()).isEqualTo("a-hash");
        assertThat(token.familyId()).isEqualTo(familyId);
        assertThat(token.expiresAt()).isEqualTo(expiresAt);
        assertThat(token.usedAt()).isEqualTo(usedAt);
        assertThat(token.revokedAt()).isEqualTo(revokedAt);
    }

    @Test
    void issueRejectsNullZeroOrNegativeTtl() {
        assertThatThrownBy(() -> RefreshToken.issue(accountId, UUID.randomUUID(), null, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(accountId, UUID.randomUUID(), Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
