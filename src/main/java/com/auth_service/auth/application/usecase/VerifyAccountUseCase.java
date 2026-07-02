package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * FR-2 — consume un Token de Verificación y activa la Cuenta asociada.
 * A diferencia del registro, aquí el error SÍ es distinguible del éxito: el
 * usuario ya posee el token (se lo enviamos por email), así que no hay
 * enumeración de cuentas ajenas en juego.
 */
@Service
public class VerifyAccountUseCase {

    private final VerificationTokenRepository verificationTokenRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public VerifyAccountUseCase(VerificationTokenRepository verificationTokenRepository,
                                 AccountRepository accountRepository,
                                 Clock clock) {
        this.verificationTokenRepository = verificationTokenRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    @Transactional
    public void verify(String rawToken) {
        String tokenHash = VerificationToken.hashRawToken(rawToken);

        VerificationToken token = verificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new DomainValidationException("Token de verificación inválido."));

        if (token.purpose() != VerificationPurpose.EMAIL_VERIFICATION) {
            throw new DomainValidationException("Token de verificación inválido.");
        }

        // Si el token ya fue usado o expiró, consume() lanza aquí y Account.activate()
        // nunca se ejecuta — así AC #2 ("la Cuenta permanece en su estado anterior")
        // se cumple por construcción, sin necesitar revertir nada manualmente.
        VerificationToken consumedToken = token.consume(clock);
        verificationTokenRepository.save(consumedToken);

        Account account = accountRepository.findById(token.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cuenta no encontrada para el token verificado: " + token.accountId()));
        account.activate();
        accountRepository.save(account);
    }
}
