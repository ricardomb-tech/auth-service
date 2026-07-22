package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.exception.SelfManagementNotAllowedException;
import com.auth_service.auth.domain.exception.TargetAccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.AuditLogRepository;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManageAccountUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC);
    private final ManageAccountUseCase useCase =
            new ManageAccountUseCase(accountRepository, refreshTokenRepository, auditLogRepository, clock);

    private Account activeAccount(AccountId id) {
        return Account.reconstitute(id, new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
    }

    private Account activeAdminAccount(AccountId id) {
        return Account.reconstitute(id, new Email("admin@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.ADMIN, Role.USER), 0, null, Instant.now());
    }

    @Test
    void listAccountsReturnsPageFromRepository() {
        Account account = activeAccount(AccountId.newId());
        when(accountRepository.findAllPaged(0, 20)).thenReturn(List.of(account));
        when(accountRepository.countAll()).thenReturn(1L);

        ManageAccountUseCase.AccountPage result = useCase.listAccounts(0, 20);

        assertThat(result.content()).containsExactly(account);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1L);
    }

    @Test
    void listAccountsWithNegativePageThrowsDomainValidationException() {
        assertThatThrownBy(() -> useCase.listAccounts(-1, 20)).isInstanceOf(DomainValidationException.class);
        verify(accountRepository, never()).findAllPaged(anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void listAccountsWithSizeOutOfRangeThrowsDomainValidationException(int size) {
        assertThatThrownBy(() -> useCase.listAccounts(0, size)).isInstanceOf(DomainValidationException.class);
        verify(accountRepository, never()).findAllPaged(anyInt(), anyInt());
    }

    @Test
    void getAccountDetailReturnsAccountWhenFound() {
        AccountId targetId = AccountId.newId();
        Account account = activeAccount(targetId);
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(account));

        Account result = useCase.getAccountDetail(targetId.value().toString());

        assertThat(result).isEqualTo(account);
    }

    @Test
    void getAccountDetailWithUnknownIdThrowsTargetAccountNotFoundException() {
        AccountId targetId = AccountId.newId();
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> useCase.getAccountDetail(targetId.value().toString()))
                .isInstanceOf(TargetAccountNotFoundException.class);
    }

    @Test
    void getAccountDetailWithMalformedIdThrowsTargetAccountNotFoundException() {
        assertThatThrownBy(() -> useCase.getAccountDetail("no-es-un-uuid"))
                .isInstanceOf(TargetAccountNotFoundException.class);
    }

    @Test
    void disableAccountTransitionsToDisabledRevokesTokensAndAuditsSuccess() {
        AccountId actorId = AccountId.newId();
        AccountId targetId = AccountId.newId();
        Account target = activeAccount(targetId);
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        Account result = useCase.disableAccount(actorId.value().toString(), targetId.value().toString());

        assertThat(result.status()).isEqualTo(AccountStatus.DISABLED);
        InOrder inOrder = inOrder(refreshTokenRepository, accountRepository);
        inOrder.verify(refreshTokenRepository).revokeAllForAccount(eq(targetId), any());
        inOrder.verify(accountRepository).save(target);
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.SUCCESS && e.action() == AdminAction.DISABLE_ACCOUNT
                        && e.actorAccountId().equals(actorId) && e.targetAccountId().equals(targetId)));
    }

    @Test
    void disableAccountOnSelfIsRejectedAuditedAndDoesNotTouchAccountOrTokens() {
        AccountId selfId = AccountId.newId();
        Account self = activeAdminAccount(selfId);
        when(accountRepository.findById(selfId)).thenReturn(java.util.Optional.of(self));

        assertThatThrownBy(() -> useCase.disableAccount(selfId.value().toString(), selfId.value().toString()))
                .isInstanceOf(SelfManagementNotAllowedException.class);

        assertThat(self.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.REJECTED_SELF && e.action() == AdminAction.DISABLE_ACCOUNT));
    }

    @Test
    void reactivateAccountTransitionsToActiveAndAuditsSuccess() {
        AccountId actorId = AccountId.newId();
        AccountId targetId = AccountId.newId();
        Account target = Account.reconstitute(targetId, new Email("titular@example.com"), new HashedPassword("hash"),
                AccountStatus.DISABLED, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        Account result = useCase.reactivateAccount(actorId.value().toString(), targetId.value().toString());

        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository).save(target);
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.SUCCESS && e.action() == AdminAction.REACTIVATE_ACCOUNT));
    }

    @Test
    void reactivateAccountOnSelfIsRejectedAuditedAndDoesNotTouchAccount() {
        AccountId selfId = AccountId.newId();
        Account self = Account.reconstitute(selfId, new Email("admin@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.DISABLED, Set.of(Role.ADMIN, Role.USER), 0, null, Instant.now());
        when(accountRepository.findById(selfId)).thenReturn(java.util.Optional.of(self));

        assertThatThrownBy(() -> useCase.reactivateAccount(selfId.value().toString(), selfId.value().toString()))
                .isInstanceOf(SelfManagementNotAllowedException.class);

        assertThat(self.status()).isEqualTo(AccountStatus.DISABLED);
        verify(accountRepository, never()).save(any());
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.REJECTED_SELF && e.action() == AdminAction.REACTIVATE_ACCOUNT));
    }

    @Test
    void updateRolesReplacesRolesAndAuditsSuccess() {
        AccountId actorId = AccountId.newId();
        AccountId targetId = AccountId.newId();
        Account target = activeAccount(targetId);
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        Account result = useCase.updateRoles(actorId.value().toString(), targetId.value().toString(), Set.of("ADMIN", "USER"));

        assertThat(result.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        verify(accountRepository).save(target);
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.SUCCESS && e.action() == AdminAction.UPDATE_ROLES));
    }

    @Test
    void updateRolesRemovingAdminFromSelfIsRejectedAuditedAndDoesNotTouchAccount() {
        AccountId selfId = AccountId.newId();
        Account self = activeAdminAccount(selfId);
        when(accountRepository.findById(selfId)).thenReturn(java.util.Optional.of(self));

        assertThatThrownBy(() -> useCase.updateRoles(selfId.value().toString(), selfId.value().toString(), Set.of("USER")))
                .isInstanceOf(SelfManagementNotAllowedException.class);

        assertThat(self.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        verify(accountRepository, never()).save(any());
        verify(auditLogRepository).save(argThat(e ->
                e.result() == AuditResult.REJECTED_SELF && e.action() == AdminAction.UPDATE_ROLES));
    }

    @Test
    void updateRolesRemovingAdminFromAnotherAccountIsAllowed() {
        AccountId actorId = AccountId.newId();
        AccountId targetId = AccountId.newId();
        Account target = Account.reconstitute(targetId, new Email("otro-admin@example.com"), new HashedPassword("hash"),
                AccountStatus.ACTIVE, Set.of(Role.ADMIN, Role.USER), 0, null, Instant.now());
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        Account result = useCase.updateRoles(actorId.value().toString(), targetId.value().toString(), Set.of("USER"));

        assertThat(result.roles()).containsExactly(Role.USER);
        verify(accountRepository).save(target);
    }

    @Test
    void updateRolesWithEmptySetThrowsDomainValidationExceptionBeforeSelfCheck() {
        AccountId selfId = AccountId.newId();
        Account self = activeAdminAccount(selfId);
        when(accountRepository.findById(selfId)).thenReturn(java.util.Optional.of(self));

        assertThatThrownBy(() -> useCase.updateRoles(selfId.value().toString(), selfId.value().toString(), Set.of()))
                .isInstanceOf(DomainValidationException.class);

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void updateRolesWithUnrecognizedRoleValueThrowsDomainValidationException() {
        AccountId actorId = AccountId.newId();
        AccountId targetId = AccountId.newId();
        Account target = activeAccount(targetId);
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        assertThatThrownBy(() -> useCase.updateRoles(actorId.value().toString(), targetId.value().toString(), Set.of("SUPERUSER")))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void malformedActorSubjectThrowsAccountNotFoundException() {
        AccountId targetId = AccountId.newId();
        Account target = activeAccount(targetId);
        when(accountRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        assertThatThrownBy(() -> useCase.disableAccount("no-es-un-uuid", targetId.value().toString()))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(target.status()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeAllForAccount(any(), any());
        verify(auditLogRepository, never()).save(any());
    }
}
