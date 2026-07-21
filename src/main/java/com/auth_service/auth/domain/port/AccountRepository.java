package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.Role;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findByEmail(Email email);

    Optional<Account> findById(AccountId id);

    boolean existsByRole(Role role);
}
