package com.auth_service.auth.infrastructure.adapters.security;

import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordHasherTest {

    private BCryptPasswordHasher hasher;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        hasher = new BCryptPasswordHasher(passwordEncoder);
    }

    @Test
    void matchesReturnsTrueForTheSamePasswordThatWasHashed() {
        HashedPassword hashed = hasher.hash(new RawPassword("Str0ngPass"));

        assertThat(hasher.matches("Str0ngPass", hashed)).isTrue();
    }

    @Test
    void matchesReturnsFalseForADifferentPassword() {
        HashedPassword hashed = hasher.hash(new RawPassword("Str0ngPass"));

        assertThat(hasher.matches("OtherPass1", hashed)).isFalse();
    }

    @Test
    void matchesReturnsFalseForAPasswordThatDoesNotMeetTheRawPasswordPolicyWithoutThrowing() {
        HashedPassword hashed = hasher.hash(new RawPassword("Str0ngPass"));

        assertThat(hasher.matches("short", hashed)).isFalse();
    }
}
