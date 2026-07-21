package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-12 — aprovisiona la Cuenta ADMIN inicial en el arranque. Invocado
 * exclusivamente por {@code AdminBootstrapRunner} (config/, único llamador
 * permitido — AD-6: la mutación vive aquí, el ApplicationRunner es solo el
 * punto de entrada de infraestructura, igual que un controller delega en
 * un caso de uso en vez de tocar el repositorio directamente).
 */
@Service
public class ProvisionInitialAdminUseCase {

    private final AccountRepository accountRepository;
    private final PasswordHasher passwordHasher;

    public ProvisionInitialAdminUseCase(AccountRepository accountRepository, PasswordHasher passwordHasher) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public ProvisionInitialAdminResult provision(String rawEmail, String rawPassword) {
        if (accountRepository.existsByRole(Role.ADMIN)) {
            return ProvisionInitialAdminResult.ADMIN_ALREADY_EXISTS;
        }
        if (rawEmail == null || rawEmail.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            return ProvisionInitialAdminResult.MISSING_CONFIGURATION;
        }

        Email email = new Email(rawEmail);
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword(rawPassword));
        Account admin = Account.registerAdmin(email, hashedPassword);
        accountRepository.save(admin);

        return ProvisionInitialAdminResult.ADMIN_CREATED;
    }
}
