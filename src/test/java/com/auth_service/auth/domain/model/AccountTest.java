package com.auth_service.auth.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void registerCreatesAccountPendingVerificationWithUserRole() {
        Email email = new Email("visitante@example.com");
        HashedPassword hashed = new HashedPassword("bcrypt-hash");

        Account account = Account.register(email, hashed);

        assertThat(account.id()).isNotNull();
        assertThat(account.email()).isEqualTo(email);
        assertThat(account.passwordHash()).isEqualTo(hashed);
        assertThat(account.status()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(account.roles()).containsExactly(Role.USER);
        assertThat(account.failedAttempts()).isZero();
        assertThat(account.lockedUntil()).isNull();
        assertThat(account.createdAt()).isNotNull();
    }

    @Test
    void eachRegistrationGetsAUniqueId() {
        Email email = new Email("visitante@example.com");
        HashedPassword hashed = new HashedPassword("bcrypt-hash");

        Account first = Account.register(email, hashed);
        Account second = Account.register(email, hashed);

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
