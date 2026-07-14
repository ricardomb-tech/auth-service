package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final LoginUseCase useCase = new LoginUseCase(accountRepository, passwordHasher, tokenIssuer);

    private Account activeAccount() {
        return Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
    }

    @Test
    void validCredentialsOnActiveAccountIssuesTokens() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);
        TokenIssuer.IssuedTokens issuedTokens = new TokenIssuer.IssuedTokens("access", "refresh", 900L);
        when(tokenIssuer.issue(account)).thenReturn(issuedTokens);

        TokenIssuer.IssuedTokens result = useCase.login(new LoginCommand("titular@example.com", "Str0ngPass"));

        assertThat(result).isEqualTo(issuedTokens);
    }

    @Test
    void nonExistentEmailThrowsAuthenticationFailedWithoutIssuingTokens() {
        when(accountRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login(new LoginCommand("nadie@example.com", "Str0ngPass")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void wrongPasswordThrowsAuthenticationFailedWithoutIssuingTokens() {
        Account account = activeAccount();
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "WrongPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING_VERIFICATION", "LOCKED", "DISABLED"})
    void nonActiveAccountWithCorrectPasswordThrowsAuthenticationFailedWithoutIssuingTokens(AccountStatus status) {
        Account account = Account.reconstitute(AccountId.newId(), new Email("titular@example.com"), new HashedPassword("bcrypt-hash"),
                status, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));
        when(passwordHasher.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> useCase.login(new LoginCommand("titular@example.com", "Str0ngPass")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void federatedOnlyAccountWithNullPasswordHashThrowsAuthenticationFailedWithoutCallingPasswordHasher() {
        // Story 2.1: una Cuenta creada solo vía Google no tiene password_hash.
        // Con el PasswordHasher real (BCryptPasswordHasher), matches(raw, null)
        // lanza NullPointerException -> 500, una fuga de enumeración. El mock
        // de este test no reproduce esa NPE (un mock no ejecuta lógica real),
        // así que la aserción correcta es que el guard impide llegar siquiera
        // a invocar matches() cuando passwordHash es null.
        Account account = Account.reconstitute(AccountId.newId(), new Email("federado@example.com"), null,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        when(accountRepository.findByEmail(any())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> useCase.login(new LoginCommand("federado@example.com", "CualquierPass1")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(passwordHasher, never()).matches(any(), any());
        verify(tokenIssuer, never()).issue(any());
    }
}
