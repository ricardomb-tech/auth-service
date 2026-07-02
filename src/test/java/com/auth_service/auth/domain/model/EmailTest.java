package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void acceptsValidFormat() {
        Email email = new Email("user@example.com");
        assertThat(email.value()).isEqualTo("user@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "missing-domain@", "@missing-local.com", "no-at-sign.com", ""})
    void rejectsInvalidFormat(String invalid) {
        assertThatThrownBy(() -> new Email(invalid))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new Email(null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void normalizesToLowercaseAndTrims() {
        Email email = new Email("  User@Example.COM  ");
        assertThat(email.value()).isEqualTo("user@example.com");
    }

    @Test
    void twoEmailsDifferingOnlyByCaseAreEqual() {
        assertThat(new Email("User@Example.com")).isEqualTo(new Email("user@example.com"));
    }
}
