package com.auth_service.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationTokenTest {

    @Test
    void issueReturnsRawTokenSeparateFromPersistedHash() {
        AccountId accountId = AccountId.newId();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);

        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), fixedClock);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.token().tokenHash()).isNotBlank();
        // El hash persistido nunca debe coincidir textualmente con el token crudo (AD-5).
        assertThat(issued.token().tokenHash()).isNotEqualTo(issued.rawToken());
    }

    @Test
    void issueSetsExpiryAtTtlFromClock() {
        AccountId accountId = AccountId.newId();
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), fixedClock);

        assertThat(issued.token().expiresAt()).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(issued.token().consumedAt()).isNull();
        assertThat(issued.token().accountId()).isEqualTo(accountId);
        assertThat(issued.token().purpose()).isEqualTo(VerificationPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void rejectsNullTtl() {
        assertThatThrownBy(() -> VerificationToken.issue(AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroOrNegativeTtl() {
        assertThatThrownBy(() -> VerificationToken.issue(AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ZERO, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VerificationToken.issue(AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(-1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumeReturnsNewInstanceWithConsumedAtSet() {
        Clock issuedAt = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
        Clock consumedAt = Clock.fixed(Instant.parse("2026-07-02T01:00:00Z"), ZoneOffset.UTC);
        VerificationToken.Issued issued = VerificationToken.issue(
                AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), issuedAt);

        VerificationToken consumed = issued.token().consume(consumedAt);

        assertThat(consumed.consumedAt()).isEqualTo(Instant.parse("2026-07-02T01:00:00Z"));
        assertThat(consumed.id()).isEqualTo(issued.token().id());
        assertThat(consumed.tokenHash()).isEqualTo(issued.token().tokenHash());
        assertThat(consumed.accountId()).isEqualTo(issued.token().accountId());
        assertThat(consumed.purpose()).isEqualTo(issued.token().purpose());
        assertThat(consumed.expiresAt()).isEqualTo(issued.token().expiresAt());
        // El objeto original no muta.
        assertThat(issued.token().consumedAt()).isNull();
    }

    @Test
    void consumeRejectsAlreadyConsumedToken() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
        VerificationToken.Issued issued = VerificationToken.issue(
                AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        VerificationToken alreadyConsumed = issued.token().consume(clock);

        assertThatThrownBy(() -> alreadyConsumed.consume(clock))
                .isInstanceOf(com.auth_service.auth.domain.exception.DomainValidationException.class)
                .hasMessageContaining("ya fue utilizado");
    }

    @Test
    void consumeRejectsExpiredToken() {
        Clock issuedAt = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
        Clock afterExpiry = Clock.fixed(Instant.parse("2026-07-03T00:00:01Z"), ZoneOffset.UTC);
        VerificationToken.Issued issued = VerificationToken.issue(
                AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), issuedAt);

        assertThatThrownBy(() -> issued.token().consume(afterExpiry))
                .isInstanceOf(com.auth_service.auth.domain.exception.DomainValidationException.class)
                .hasMessageContaining("expirado");
    }

    @Test
    void hashRawTokenIsDeterministicAndMatchesIssuedHash() {
        Clock clock = Clock.systemUTC();
        VerificationToken.Issued issued = VerificationToken.issue(
                AccountId.newId(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);

        String recomputedHash = VerificationToken.hashRawToken(issued.rawToken());

        assertThat(recomputedHash).isEqualTo(issued.token().tokenHash());
        assertThat(VerificationToken.hashRawToken("same-input")).isEqualTo(VerificationToken.hashRawToken("same-input"));
    }

    @Test
    void eachIssuanceProducesADifferentRawTokenAndHash() {
        AccountId accountId = AccountId.newId();
        Clock clock = Clock.systemUTC();

        VerificationToken.Issued first = VerificationToken.issue(accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        VerificationToken.Issued second = VerificationToken.issue(accountId, VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);

        assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
        assertThat(first.token().tokenHash()).isNotEqualTo(second.token().tokenHash());
    }
}
