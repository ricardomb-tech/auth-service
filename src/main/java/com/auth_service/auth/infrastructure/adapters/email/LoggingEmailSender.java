package com.auth_service.auth.infrastructure.adapters.email;

import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Adapter de desarrollo — loggea el enlace en vez de enviarlo por SMTP real
 * (AD-9). El proveedor SMTP de producción es la pregunta abierta 1 del PRD;
 * mientras no se resuelva, esta es la única implementación de {@link EmailSender}.
 *
 * <p>{@code @Profile("!prod")}: excluida a propósito del perfil de
 * producción — es preferible que el arranque falle por falta de un bean
 * {@link EmailSender} a que este adapter loggee tokens de verificación en
 * claro en logs de producción.</p>
 */
@Component
@Profile("!prod")
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final String appBaseUrl;

    public LoggingEmailSender(@Value("${APP_BASE_URL:http://localhost:8080}") String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    @Override
    public void sendVerificationEmail(Email recipient, String rawToken) {
        String link = appBaseUrl + "/auth/verify?token=" + rawToken;
        log.info("[dev] Email de verificación para {} -> {}", recipient.value(), link);
    }

    @Override
    public void sendPasswordResetEmail(Email recipient, String rawToken) {
        String link = appBaseUrl + "/auth/reset-password?token=" + rawToken;
        log.info("[dev] Email de recuperación de contraseña para {} -> {}", recipient.value(), link);
    }
}
