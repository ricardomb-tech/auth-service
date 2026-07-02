package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.stereotype.Component;

@Component
public class VerificationTokenRepositoryAdapter implements VerificationTokenRepository {

    private final VerificationTokenJpaRepository jpaRepository;

    public VerificationTokenRepositoryAdapter(VerificationTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public VerificationToken save(VerificationToken token) {
        VerificationTokenEntity entity = new VerificationTokenEntity(
                token.id(),
                token.accountId().value(),
                token.tokenHash(),
                token.purpose().name(),
                token.expiresAt(),
                token.consumedAt());
        jpaRepository.save(entity);
        return token;
    }
}
