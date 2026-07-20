package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * FR-7 — consume un Token {@code PASSWORD_RESET} y aplica la nueva
 * contraseña. Igual que {@code VerifyAccountUseCase}, el error SÍ es
 * distinguible del éxito aquí: el visitante ya posee el token del email, no
 * hay enumeración de cuentas ajenas en juego (AC #4).
 */
@Service
public class ResetPasswordUseCase {

    private final VerificationTokenRepository verificationTokenRepository;
    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public ResetPasswordUseCase(VerificationTokenRepository verificationTokenRepository,
                                 AccountRepository accountRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 PasswordHasher passwordHasher,
                                 Clock clock) {
        this.verificationTokenRepository = verificationTokenRepository;
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    public void resetPassword(ResetPasswordCommand command) {
        String tokenHash = VerificationToken.hashRawToken(command.rawToken());

        VerificationToken token = verificationTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new DomainValidationException("Token de recuperación inválido."));

        if (token.purpose() != VerificationPurpose.PASSWORD_RESET) {
            throw new DomainValidationException("Token de recuperación inválido.");
        }

        // Validar la contraseña nueva ANTES de consumir el token (AC #5): si
        // RawPassword rechaza el formato, el token sigue disponible para un
        // reintento — a diferencia de VerifyAccountUseCase, aquí sí hay un
        // input adicional que puede fallar tras encontrar el token.
        RawPassword rawPassword = new RawPassword(command.newRawPassword());
        HashedPassword hashedPassword = passwordHasher.hash(rawPassword);

        // Si el token ya fue usado o expiró, consume() lanza aquí y ni la
        // Cuenta ni las Familias de Refresh Tokens se tocan (AC #4).
        VerificationToken consumedToken = token.consume(clock);

        // revokeAllForAccount es un @Modifying(clearAutomatically = true) —
        // limpia el contexto de persistencia de Hibernate. Debe ejecutarse
        // ANTES de cualquier save() de esta transacción: si corriera después,
        // borraría del contexto los cambios de save() aún no flusheados
        // (merge() no ejecuta UPDATE de inmediato), perdiéndolos en
        // silencio — ni el token quedaría consumido ni la contraseña
        // actualizada en BD pese a que el caso de uso "termina" sin error.
        refreshTokenRepository.revokeAllForAccount(token.accountId(), Instant.now(clock));

        verificationTokenRepository.save(consumedToken);

        Account account = accountRepository.findById(token.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cuenta no encontrada para el token de recuperación: " + token.accountId()));
        account.changePassword(hashedPassword);
        accountRepository.save(account);
    }
}
