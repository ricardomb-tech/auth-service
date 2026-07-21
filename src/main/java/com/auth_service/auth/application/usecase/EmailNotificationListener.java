package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envía el email de verificación después de que la transacción de negocio
 * ya confirmó — un fallo aquí no puede revertir nada, la transacción ya
 * cerró (AD-9).
 */
@Component
public class EmailNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationListener.class);

    private final EmailSender emailSender;

    public EmailNotificationListener(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(VerificationEmailRequested event) {
        try {
            emailSender.sendVerificationEmail(event.recipient(), event.rawToken());
        } catch (RuntimeException ex) {
            log.error("Fallo al enviar email de verificación a accountEmail={} (no afecta la transacción ya confirmada)",
                    event.recipient(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetEmailRequested(PasswordResetEmailRequested event) {
        try {
            emailSender.sendPasswordResetEmail(event.recipient(), event.rawToken());
        } catch (RuntimeException ex) {
            log.error("Fallo al enviar email de recuperación de contraseña a accountEmail={} (no afecta la transacción ya confirmada)",
                    event.recipient(), ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountLockedEmailRequested(AccountLockedEmailRequested event) {
        try {
            emailSender.sendAccountLockedEmail(event.recipient(), event.lockedUntil());
        } catch (RuntimeException ex) {
            log.error("Fallo al enviar email de bloqueo por fuerza bruta a accountEmail={} (no afecta la transacción ya confirmada)",
                    event.recipient(), ex);
        }
    }
}
