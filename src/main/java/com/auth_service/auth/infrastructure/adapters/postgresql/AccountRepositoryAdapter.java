package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AccountRepositoryAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    public AccountRepositoryAdapter(AccountJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = toEntity(account);
        AccountEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Account> findByEmail(Email email) {
        return jpaRepository.findByEmail(email.value()).map(this::toDomain);
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return jpaRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public boolean existsByRole(Role role) {
        return jpaRepository.existsByRolesContaining(role);
    }

    private AccountEntity toEntity(Account account) {
        return new AccountEntity(
                account.id().value(),
                account.email().value(),
                account.passwordHash() != null ? account.passwordHash().value() : null,
                account.status().name(),
                account.failedAttempts(),
                account.lockedUntil(),
                account.createdAt(),
                account.roles());
    }

    private Account toDomain(AccountEntity entity) {
        HashedPassword hashedPassword = entity.getPasswordHash() != null
                ? new HashedPassword(entity.getPasswordHash())
                : null;
        return Account.reconstitute(
                new AccountId(entity.getId()),
                new Email(entity.getEmail()),
                hashedPassword,
                AccountStatus.valueOf(entity.getStatus()),
                entity.getRoles(),
                entity.getFailedAttempts(),
                entity.getLockedUntil(),
                entity.getCreatedAt());
    }
}
