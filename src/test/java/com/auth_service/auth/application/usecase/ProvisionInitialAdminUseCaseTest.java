package com.auth_service.auth.application.usecase;

import com.auth_service.auth.application.usecase.result.ProvisionInitialAdminResult;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProvisionInitialAdminUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final ProvisionInitialAdminUseCase useCase =
            new ProvisionInitialAdminUseCase(accountRepository, passwordHasher);

    @Test
    void adminAlreadyExistsSkipsCreationAndReturnsAlreadyExists() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        ProvisionInitialAdminResult result = useCase.provision("admin@example.com", "Str0ngAdminPass1");

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.ADMIN_ALREADY_EXISTS);
        verify(accountRepository, never()).save(any());
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void adminAlreadyExistsSkipsCreationAndReturnsAlreadyExistsEvenWithoutConfiguration() {
        // AC #2 de la Story 4.1: si ya existe un ADMIN, no se crea ni
        // modifica ninguna Cuenta "independientemente de si
        // AUTH_ADMIN_EMAIL/AUTH_ADMIN_PASSWORD están definidos o no".
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        ProvisionInitialAdminResult result = useCase.provision(null, null);

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.ADMIN_ALREADY_EXISTS);
        verify(accountRepository, never()).save(any());
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void missingEmailAndPasswordWithNoAdminReturnsMissingConfiguration() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        ProvisionInitialAdminResult result = useCase.provision(null, null);

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.MISSING_CONFIGURATION);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void blankEmailTreatedAsMissingConfiguration() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        ProvisionInitialAdminResult result = useCase.provision("", "Str0ngAdminPass1");

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.MISSING_CONFIGURATION);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void blankPasswordTreatedAsMissingConfiguration() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        ProvisionInitialAdminResult result = useCase.provision("admin@example.com", "");

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.MISSING_CONFIGURATION);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void validConfigurationWithNoAdminCreatesActiveAdminAccount() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        HashedPassword hashed = new HashedPassword("bcrypt-hash");
        when(passwordHasher.hash(any())).thenReturn(hashed);

        ProvisionInitialAdminResult result = useCase.provision("admin@example.com", "Str0ngAdminPass1");

        assertThat(result).isEqualTo(ProvisionInitialAdminResult.ADMIN_CREATED);
        verify(accountRepository).save(argThat(a ->
                a.status() == AccountStatus.ACTIVE && a.roles().containsAll(Set.of(Role.ADMIN, Role.USER))));
    }

    @Test
    void invalidEmailFormatPropagatesDomainValidationException() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> useCase.provision("no-es-un-email", "Str0ngAdminPass1"))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void weakPasswordPropagatesDomainValidationException() {
        when(accountRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> useCase.provision("admin@example.com", "corta"))
                .isInstanceOf(DomainValidationException.class);

        verify(accountRepository, never()).save(any());
    }
}
