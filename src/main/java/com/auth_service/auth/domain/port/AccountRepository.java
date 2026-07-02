package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;

import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findByEmail(Email email);
}
