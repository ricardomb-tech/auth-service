package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.Email;

public interface EmailSender {

    void sendVerificationEmail(Email recipient, String rawToken);

    void sendPasswordResetEmail(Email recipient, String rawToken);
}
