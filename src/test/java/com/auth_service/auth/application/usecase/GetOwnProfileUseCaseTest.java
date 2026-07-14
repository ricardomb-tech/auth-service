package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetOwnProfileUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final GetOwnProfileUseCase useCase = new GetOwnProfileUseCase(accountRepository);

    private Account accountWith(AccountId id) {
        HashedPassword hashedPassword = new HashedPassword("$2a$10$irrelevantIrrelevantIrrelevantIrrelevantIrrelevantIrre");
        return Account.reconstitute(id, new Email("titular@example.com"), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void validAccountIdWithExistingAccountReturnsIt() {
        AccountId accountId = AccountId.newId();
        Account account = accountWith(accountId);
        when(accountRepository.findById(eq(accountId))).thenReturn(Optional.of(account));

        Account result = useCase.getOwnProfile(new GetOwnProfileCommand(accountId.value().toString()));

        assertThat(result).isSameAs(account);
        verify(accountRepository).findById(eq(accountId));
    }

    @Test
    void validAccountIdWithoutAccountThrowsAccountNotFoundException() {
        AccountId accountId = AccountId.newId();
        when(accountRepository.findById(eq(accountId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getOwnProfile(new GetOwnProfileCommand(accountId.value().toString())))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void nonUuidAccountIdThrowsAccountNotFoundExceptionWithoutPropagatingIllegalArgumentException() {
        assertThatThrownBy(() -> useCase.getOwnProfile(new GetOwnProfileCommand("not-a-uuid")))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void nullAccountIdThrowsAccountNotFoundExceptionWithoutPropagatingNullPointerException() {
        assertThatThrownBy(() -> useCase.getOwnProfile(new GetOwnProfileCommand(null)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void nonActiveAccountIsReturnedAsIsWithItsStatus() {
        // Decisión documentada en la Story 1.7: una Cuenta no-ACTIVE con token
        // vigente ve su propio perfil — el status viaja en la respuesta para
        // que el cliente lo refleje; el caso de uso no filtra por estado.
        AccountId accountId = AccountId.newId();
        HashedPassword hashedPassword = new HashedPassword("$2a$10$irrelevantIrrelevantIrrelevantIrrelevantIrrelevantIrre");
        Account lockedAccount = Account.reconstitute(accountId, new Email("titular@example.com"), hashedPassword,
                AccountStatus.LOCKED, Set.of(Role.USER), 5, Instant.parse("2026-07-13T00:15:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"));
        when(accountRepository.findById(eq(accountId))).thenReturn(Optional.of(lockedAccount));

        Account result = useCase.getOwnProfile(new GetOwnProfileCommand(accountId.value().toString()));

        assertThat(result).isSameAs(lockedAccount);
        assertThat(result.status()).isEqualTo(AccountStatus.LOCKED);
    }
}
