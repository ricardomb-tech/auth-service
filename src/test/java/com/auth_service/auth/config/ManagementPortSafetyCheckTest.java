package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementPortSafetyCheckTest {

    @Test
    void doesNotThrowWhenPortsDiffer() {
        assertThatCode(() -> new ManagementPortSafetyCheck(8080, 8081).verifyPortsAreDistinct())
                .doesNotThrowAnyException();
    }

    @Test
    void throwsWhenBusinessAndManagementPortsCoincide() {
        assertThatThrownBy(() -> new ManagementPortSafetyCheck(8081, 8081).verifyPortsAreDistinct())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8081");
    }

    @Test
    void doesNotThrowWhenBothPortsAreZeroRandomAssignment() {
        assertThatCode(() -> new ManagementPortSafetyCheck(0, 0).verifyPortsAreDistinct())
                .doesNotThrowAnyException();
    }
}
