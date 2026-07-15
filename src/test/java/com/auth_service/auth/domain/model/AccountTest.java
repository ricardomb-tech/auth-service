package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void activateTransitionsPendingVerificationToActive() {
        Account account = Account.register(new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"));

        account.activate();

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void activateRejectsAccountNotPendingVerification() {
        Account activeAccount = Account.reconstitute(
                AccountId.newId(), new Email("visitante@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());

        assertThatThrownBy(activeAccount::activate)
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void registerFederatedCreatesActiveAccountWithUserRoleAndNoPasswordHash() {
        Email email = new Email("federado@example.com");

        Account account = Account.registerFederated(email);

        assertThat(account.id()).isNotNull();
        assertThat(account.email()).isEqualTo(email);
        assertThat(account.passwordHash()).isNull();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.roles()).containsExactly(Role.USER);
        assertThat(account.failedAttempts()).isZero();
        assertThat(account.lockedUntil()).isNull();
        assertThat(account.createdAt()).isNotNull();
    }

    @Test
    void eachFederatedRegistrationGetsAUniqueId() {
        Email email = new Email("federado@example.com");

        Account first = Account.registerFederated(email);
        Account second = Account.registerFederated(email);

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
