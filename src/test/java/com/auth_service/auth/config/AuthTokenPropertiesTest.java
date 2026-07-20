package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthTokenPropertiesTest {

    @Test
    void appliesDefaultsWhenAllTtlsAreNull() {
        AuthTokenProperties properties = new AuthTokenProperties(null, null, null);

        assertThat(properties.verificationTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.passwordResetTtl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void keepsExplicitValuesWhenProvided() {
        AuthTokenProperties properties = new AuthTokenProperties(Duration.ofHours(1), Duration.ofDays(30), Duration.ofMinutes(30));

        assertThat(properties.verificationTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.passwordResetTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsZeroRefreshTtl() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, Duration.ZERO, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeRefreshTtl() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, Duration.ofDays(-1), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsRefreshTtlLongerThanNinetyDays() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, Duration.ofDays(91), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsRefreshTtlExactlyNinetyDays() {
        AuthTokenProperties properties = new AuthTokenProperties(null, Duration.ofDays(90), null);

        assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void rejectsZeroPasswordResetTtl() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, null, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativePasswordResetTtl() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, null, Duration.ofHours(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPasswordResetTtlLongerThanNinetyDays() {
        assertThatThrownBy(() -> new AuthTokenProperties(null, null, Duration.ofDays(91)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsPasswordResetTtlExactlyNinetyDays() {
        AuthTokenProperties properties = new AuthTokenProperties(null, null, Duration.ofDays(90));

        assertThat(properties.passwordResetTtl()).isEqualTo(Duration.ofDays(90));
    }
}
