package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockoutPropertiesTest {

    @Test
    void appliesDefaultsWhenBothAreNull() {
        LockoutProperties properties = new LockoutProperties(null, null);

        assertThat(properties.threshold()).isEqualTo(5);
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void keepsExplicitValuesWhenProvided() {
        LockoutProperties properties = new LockoutProperties(3, Duration.ofMinutes(30));

        assertThat(properties.threshold()).isEqualTo(3);
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsZeroThreshold() {
        assertThatThrownBy(() -> new LockoutProperties(0, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeThreshold() {
        assertThatThrownBy(() -> new LockoutProperties(-1, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsZeroLockDuration() {
        assertThatThrownBy(() -> new LockoutProperties(null, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNegativeLockDuration() {
        assertThatThrownBy(() -> new LockoutProperties(null, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
