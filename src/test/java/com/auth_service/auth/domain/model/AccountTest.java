package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
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

    @Test
    void registerAdminCreatesActiveAccountWithAdminAndUserRoles() {
        Email email = new Email("admin@example.com");
        HashedPassword hashed = new HashedPassword("bcrypt-hash");

        Account account = Account.registerAdmin(email, hashed);

        assertThat(account.id()).isNotNull();
        assertThat(account.email()).isEqualTo(email);
        assertThat(account.passwordHash()).isEqualTo(hashed);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        assertThat(account.failedAttempts()).isZero();
        assertThat(account.lockedUntil()).isNull();
        assertThat(account.createdAt()).isNotNull();
    }

    @Test
    void changePasswordReplacesTheHashWithoutAffectingOtherFields() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("old-hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 3, Instant.now().plusSeconds(60), Instant.now());
        HashedPassword newHash = new HashedPassword("new-hash");

        account.changePassword(newHash);

        assertThat(account.passwordHash()).isEqualTo(newHash);
        assertThat(account.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.failedAttempts()).isEqualTo(3);
    }

    @Test
    void changePasswordWorksOnAFederatedAccountWithNoExistingHash() {
        Account account = Account.registerFederated(new Email("federado@example.com"));
        HashedPassword newHash = new HashedPassword("new-hash");

        account.changePassword(newHash);

        assertThat(account.passwordHash()).isEqualTo(newHash);
    }

    @Test
    void recordFailedLoginAttemptIncrementsWithoutLockingBelowThreshold() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 3, null, Instant.now());
        Instant now = Instant.parse("2026-07-20T00:00:00Z");

        boolean justLocked = account.recordFailedLoginAttempt(5, Duration.ofMinutes(15), now);

        assertThat(justLocked).isFalse();
        assertThat(account.failedAttempts()).isEqualTo(4);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.lockedUntil()).isNull();
    }

    @Test
    void recordFailedLoginAttemptReachingThresholdLocksTheAccount() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 4, null, Instant.now());
        Instant now = Instant.parse("2026-07-20T00:00:00Z");

        boolean justLocked = account.recordFailedLoginAttempt(5, Duration.ofMinutes(15), now);

        assertThat(justLocked).isTrue();
        assertThat(account.failedAttempts()).isEqualTo(5);
        assertThat(account.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.lockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "LOCKED", "DISABLED"})
    void recordFailedLoginAttemptIsNoOpWhenAccountIsNotActive(AccountStatus status) {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                status, Set.of(Role.USER), 4, null, Instant.now());
        Instant now = Instant.parse("2026-07-20T00:00:00Z");

        boolean justLocked = account.recordFailedLoginAttempt(5, Duration.ofMinutes(15), now);

        assertThat(justLocked).isFalse();
        assertThat(account.failedAttempts()).isEqualTo(4);
        assertThat(account.status()).isEqualTo(status);
    }

    @Test
    void recordSuccessfulLoginResetsCounterWithoutTouchingStatus() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 3, null, Instant.now());

        account.recordSuccessfulLogin();

        assertThat(account.failedAttempts()).isZero();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void unlockIfExpiredTransitionsToActiveAndResetsCounterWhenLockedUntilAlreadyPassed() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, now.minusSeconds(1), Instant.now());

        boolean unlocked = account.unlockIfExpired(now);

        assertThat(unlocked).isTrue();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.lockedUntil()).isNull();
        assertThat(account.failedAttempts()).isZero();
    }

    @Test
    void unlockIfExpiredDoesNothingWhenLockedUntilHasNotPassedYet() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, now.plusSeconds(1), Instant.now());

        boolean unlocked = account.unlockIfExpired(now);

        assertThat(unlocked).isFalse();
        assertThat(account.status()).isEqualTo(AccountStatus.LOCKED);
        assertThat(account.failedAttempts()).isEqualTo(5);
    }

    @Test
    void unlockIfExpiredDoesNothingWhenLockedUntilIsNull() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.LOCKED, Set.of(Role.USER), 5, null, Instant.now());

        boolean unlocked = account.unlockIfExpired(now);

        assertThat(unlocked).isFalse();
        assertThat(account.status()).isEqualTo(AccountStatus.LOCKED);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "ACTIVE", "DISABLED"})
    void unlockIfExpiredDoesNothingWhenAccountIsNotLocked(AccountStatus status) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                status, Set.of(Role.USER), 0, now.minusSeconds(1), Instant.now());

        boolean unlocked = account.unlockIfExpired(now);

        assertThat(unlocked).isFalse();
        assertThat(account.status()).isEqualTo(status);
    }

    @Test
    void disableTransitionsActiveAccountToDisabled() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());

        account.disable();

        assertThat(account.status()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    void disableOnAlreadyDisabledAccountThrowsDomainValidationException() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.DISABLED, Set.of(Role.USER), 0, null, Instant.now());

        assertThatThrownBy(account::disable).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void reactivateTransitionsDisabledAccountToActive() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.DISABLED, Set.of(Role.USER), 0, null, Instant.now());

        account.reactivate();

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "ACTIVE", "LOCKED"})
    void reactivateOnNonDisabledAccountThrowsDomainValidationException(AccountStatus status) {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                status, Set.of(Role.USER), 0, null, Instant.now());

        assertThatThrownBy(account::reactivate).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void updateRolesReplacesRoleSet() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());

        account.updateRoles(Set.of(Role.ADMIN, Role.USER));

        assertThat(account.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
    }

    @Test
    void updateRolesWithEmptySetThrowsDomainValidationException() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());

        assertThatThrownBy(() -> account.updateRoles(Set.of())).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void updateRolesWithNullThrowsDomainValidationException() {
        Account account = Account.reconstitute(
                AccountId.newId(), new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());

        assertThatThrownBy(() -> account.updateRoles(null)).isInstanceOf(DomainValidationException.class);
    }
}
