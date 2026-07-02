package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.VerificationToken;

public interface VerificationTokenRepository {

    VerificationToken save(VerificationToken token);
}
