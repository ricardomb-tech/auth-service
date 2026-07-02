package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawPasswordTest {

    @Test
    void acceptsPasswordMeetingPolicy() {
        RawPassword password = new RawPassword("Str0ngPass");
        assertThat(password.value()).isEqualTo("Str0ngPass");
    }

    @Test
    void rejectsShorterThan8Characters() {
        assertThatThrownBy(() -> new RawPassword("Sh0rt1"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("8 caracteres");
    }

    @Test
    void rejectsWithoutUppercase() {
        assertThatThrownBy(() -> new RawPassword("lowercase1"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("mayúscula");
    }

    @Test
    void rejectsWithoutLowercase() {
        assertThatThrownBy(() -> new RawPassword("UPPERCASE1"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("minúscula");
    }

    @Test
    void rejectsWithoutDigit() {
        assertThatThrownBy(() -> new RawPassword("NoDigitsHere"))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("dígito");
    }

    @Test
    void rejectsLongerThan72Characters() {
        String tooLong = "A1".repeat(37); // 74 caracteres, cumple política pero excede el límite de BCrypt
        assertThatThrownBy(() -> new RawPassword(tooLong))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("72 caracteres");
    }

    @Test
    void accepts72Characters() {
        String exactly72 = "Aa1" + "b".repeat(69);
        assertThat(exactly72).hasSize(72);
        assertThat(new RawPassword(exactly72).value()).hasSize(72);
    }

    @Test
    void neverExposesRawValueInToString() {
        RawPassword password = new RawPassword("Str0ngPass");
        assertThat(password.toString()).doesNotContain("Str0ngPass");
    }
}
