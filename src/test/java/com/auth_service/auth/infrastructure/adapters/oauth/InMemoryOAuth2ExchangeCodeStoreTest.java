package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOAuth2ExchangeCodeStoreTest {

    /** Reloj mutable de test — permite avanzar el tiempo entre {@code issue} y {@code redeem} sin reconstruir el store. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-14T00:00:00Z"));
    private final InMemoryOAuth2ExchangeCodeStore store = new InMemoryOAuth2ExchangeCodeStore(clock);

    private OAuth2ExchangeCodeStore.IssuedTokens tokens() {
        return new OAuth2ExchangeCodeStore.IssuedTokens("access-abc", "refresh-xyz", 900L);
    }

    @Test
    void issuedCodeCanBeRedeemedOnceForTheSameTokens() {
        String code = store.issue(tokens());

        Optional<OAuth2ExchangeCodeStore.IssuedTokens> redeemed = store.redeem(code);

        assertThat(redeemed).contains(tokens());
    }

    @Test
    void codeCannotBeRedeemedTwice() {
        String code = store.issue(tokens());
        store.redeem(code);

        Optional<OAuth2ExchangeCodeStore.IssuedTokens> secondRedeem = store.redeem(code);

        assertThat(secondRedeem).isEmpty();
    }

    @Test
    void unknownCodeReturnsEmpty() {
        assertThat(store.redeem("un-codigo-que-nunca-se-emitio")).isEmpty();
    }

    @Test
    void nullCodeReturnsEmptyWithoutThrowing() {
        assertThat(store.redeem(null)).isEmpty();
    }

    @Test
    void expiredCodeCannotBeRedeemed() {
        String code = store.issue(tokens());

        clock.advance(java.time.Duration.ofSeconds(61));

        assertThat(store.redeem(code)).isEmpty();
    }

    @Test
    void twoIssuedCodesForTheSamePayloadAreDifferentAndEachIndependentlySingleUse() {
        String firstCode = store.issue(tokens());
        String secondCode = store.issue(tokens());

        assertThat(firstCode).isNotEqualTo(secondCode);
        assertThat(store.redeem(firstCode)).contains(tokens());
        assertThat(store.redeem(secondCode)).contains(tokens());
    }
}
