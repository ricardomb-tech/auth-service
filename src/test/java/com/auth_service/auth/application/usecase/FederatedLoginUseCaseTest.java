package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.FederatedLoginFailedException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.FederatedIdentity;
import com.auth_service.auth.domain.model.FederatedProvider;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.FederatedIdentityRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederatedLoginUseCaseTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final FederatedIdentityRepository federatedIdentityRepository = mock(FederatedIdentityRepository.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);
    private final FederatedLoginUseCase useCase =
            new FederatedLoginUseCase(accountRepository, federatedIdentityRepository, tokenIssuer, clock);

    private static final TokenIssuer.IssuedTokens ISSUED_TOKENS = new TokenIssuer.IssuedTokens("access", "refresh", 900L);

    @Test
    void newVisitorWithoutAccountOrIdentityCreatesActiveAccountAndLinksIdentity() {
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-1", "nuevo@example.com", true);
        when(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-1"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail(new Email("nuevo@example.com"))).thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenIssuer.issue(any())).thenReturn(ISSUED_TOKENS);

        TokenIssuer.IssuedTokens result = useCase.login(command);

        assertThat(result).isEqualTo(ISSUED_TOKENS);
        verify(accountRepository).save(argThatAccount(account -> {
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.passwordHash()).isNull();
            assertThat(account.roles()).containsExactly(Role.USER);
            assertThat(account.email()).isEqualTo(new Email("nuevo@example.com"));
        }));
        verify(federatedIdentityRepository).save(argThatIdentity(identity -> {
            assertThat(identity.provider()).isEqualTo(FederatedProvider.GOOGLE);
            assertThat(identity.providerUserId()).isEqualTo("google-sub-1");
        }));
    }

    @Test
    void existingFederatedIdentityResolvesSameAccountWithoutCreatingDuplicate() {
        AccountId accountId = AccountId.newId();
        Account account = accountWith(accountId, "recurrente@example.com");
        FederatedIdentity identity = FederatedIdentity.reconstitute(
                java.util.UUID.randomUUID(), accountId, FederatedProvider.GOOGLE, "google-sub-2", Instant.now(clock));
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-2", "recurrente@example.com", true);
        when(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-2"))
                .thenReturn(Optional.of(identity));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(tokenIssuer.issue(account)).thenReturn(ISSUED_TOKENS);

        TokenIssuer.IssuedTokens result = useCase.login(command);

        assertThat(result).isEqualTo(ISSUED_TOKENS);
        verify(accountRepository, never()).save(any());
        verify(federatedIdentityRepository, never()).save(any());
    }

    @Test
    void noIdentityButExistingAccountWithSameEmailLinksWithoutDuplicatingAccount() {
        Account existingAccount = accountWith(AccountId.newId(), "local@example.com");
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-3", "local@example.com", true);
        when(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-3"))
                .thenReturn(Optional.empty());
        when(accountRepository.findByEmail(new Email("local@example.com"))).thenReturn(Optional.of(existingAccount));
        when(tokenIssuer.issue(existingAccount)).thenReturn(ISSUED_TOKENS);

        TokenIssuer.IssuedTokens result = useCase.login(command);

        assertThat(result).isEqualTo(ISSUED_TOKENS);
        verify(accountRepository, never()).save(any());
        verify(federatedIdentityRepository).save(argThatIdentity(identity -> {
            assertThat(identity.accountId()).isEqualTo(existingAccount.id());
            assertThat(identity.providerUserId()).isEqualTo("google-sub-3");
        }));
    }

    @Test
    void unverifiedEmailThrowsFederatedLoginFailedWithoutTouchingAnyRepository() {
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-4", "sinverificar@example.com", false);

        assertThatThrownBy(() -> useCase.login(command)).isInstanceOf(FederatedLoginFailedException.class);

        verify(accountRepository, never()).findByEmail(any());
        verify(accountRepository, never()).save(any());
        verify(federatedIdentityRepository, never()).save(any());
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void unrecognizedProviderThrowsFederatedLoginFailedWithoutPropagatingIllegalArgumentException() {
        FederatedLoginCommand command = new FederatedLoginCommand("facebook", "fb-1", "quien@example.com", true);

        assertThatThrownBy(() -> useCase.login(command)).isInstanceOf(FederatedLoginFailedException.class);

        verify(federatedIdentityRepository, never()).findByProviderAndProviderUserId(any(), any());
        verify(tokenIssuer, never()).issue(any());
    }

    @Test
    void malformedEmailThrowsFederatedLoginFailedWithoutPropagatingDomainValidationException() {
        FederatedLoginCommand command = new FederatedLoginCommand("google", "google-sub-5", "no-es-un-email", true);
        when(federatedIdentityRepository.findByProviderAndProviderUserId(FederatedProvider.GOOGLE, "google-sub-5"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.login(command)).isInstanceOf(FederatedLoginFailedException.class);

        verify(tokenIssuer, never()).issue(any());
    }

    private Account accountWith(AccountId id, String email) {
        HashedPassword hashedPassword = new HashedPassword("$2a$10$irrelevantIrrelevantIrrelevantIrrelevantIrrelevantIrre");
        return Account.reconstitute(id, new Email(email), hashedPassword, AccountStatus.ACTIVE, Set.of(Role.USER), 0, null,
                Instant.now(clock));
    }

    private Account argThatAccount(java.util.function.Consumer<Account> assertions) {
        return org.mockito.ArgumentMatchers.argThat(account -> {
            assertions.accept(account);
            return true;
        });
    }

    private FederatedIdentity argThatIdentity(java.util.function.Consumer<FederatedIdentity> assertions) {
        return org.mockito.ArgumentMatchers.argThat(identity -> {
            assertions.accept(identity);
            return true;
        });
    }
}
