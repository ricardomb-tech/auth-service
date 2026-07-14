package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void appliesDefaultsWhenAccessTtlAndIssuerAreNull() {
        JwtProperties properties = new JwtProperties("a-current-secret-at-least-32-bytes-long", null, null, null);

        assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.issuer()).isEqualTo("auth-service");
    }

    @Test
    void keepsExplicitValuesWhenProvided() {
        JwtProperties properties = new JwtProperties(
                "a-current-secret-at-least-32-bytes-long", "a-previous-secret", Duration.ofMinutes(30), "custom-issuer");

        assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.issuer()).isEqualTo("custom-issuer");
        assertThat(properties.secretPrevious()).isEqualTo("a-previous-secret");
    }

    @Test
    void rejectsNullSecretCurrent() {
        assertThatThrownBy(() -> new JwtProperties(null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankSecretCurrent() {
        assertThatThrownBy(() -> new JwtProperties("   ", null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsZeroAccessTtl() {
        assertThatThrownBy(() -> new JwtProperties("a-current-secret-at-least-32-bytes-long", null, Duration.ZERO, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeAccessTtl() {
        assertThatThrownBy(() -> new JwtProperties(
                "a-current-secret-at-least-32-bytes-long", null, Duration.ofMinutes(-1), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAccessTtlLongerThanOneDay() {
        assertThatThrownBy(() -> new JwtProperties(
                "a-current-secret-at-least-32-bytes-long", null, Duration.ofDays(1).plusMinutes(1), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsAccessTtlExactlyOneDay() {
        JwtProperties properties = new JwtProperties(
                "a-current-secret-at-least-32-bytes-long", null, Duration.ofDays(1), null);

        assertThat(properties.accessTtl()).isEqualTo(Duration.ofDays(1));
    }
}
