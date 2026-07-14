package com.auth_service.auth.infrastructure.adapters.security;

import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder passwordEncoder;

    public BCryptPasswordHasher(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public HashedPassword hash(RawPassword rawPassword) {
        return new HashedPassword(passwordEncoder.encode(rawPassword.value()));
    }

    @Override
    public boolean matches(String rawPassword, HashedPassword hashedPassword) {
        return passwordEncoder.matches(rawPassword, hashedPassword.value());
    }
}
