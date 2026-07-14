package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.port.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * FR-10 — resuelve la propia Cuenta del titular a partir del claim {@code sub}
 * ya autenticado por {@code JwtAuthenticationFilter} (AD-3, AD-13). Único caso
 * de uso de solo lectura de este Epic: no muta estado, no emite tokens.
 *
 * <p>Devuelve la Cuenta tal cual, incluido su {@code status}: una Cuenta
 * no-ACTIVE (LOCKED/DISABLED/PENDING_VERIFICATION) con un Access Token todavía
 * vigente (TTL 15 min, AD-3 stateless) ve su propio perfil — decisión
 * deliberada, la respuesta expone {@code status} precisamente para que el
 * cliente lo refleje (ver Dev Notes de la Story 1.7).</p>
 */
@Service
public class GetOwnProfileUseCase {

    private final AccountRepository accountRepository;

    public GetOwnProfileUseCase(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Account getOwnProfile(GetOwnProfileCommand command) {
        UUID accountId;
        try {
            // UUID.fromString(null) lanza NullPointerException, no
            // IllegalArgumentException — se capturan ambas para que ningún
            // sub anómalo termine en el catch-all como 500 (AC #3).
            accountId = UUID.fromString(command.accountId());
        } catch (IllegalArgumentException | NullPointerException malformedSubject) {
            throw new AccountNotFoundException("Claim sub del Access Token ausente o no es un UUID.");
        }

        return accountRepository.findById(new AccountId(accountId))
                .orElseThrow(() -> new AccountNotFoundException("Ninguna Cuenta corresponde al claim sub del Access Token."));
    }
}
