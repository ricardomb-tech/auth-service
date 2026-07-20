package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

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

    @Override
    public Optional<VerificationToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    public int invalidateActiveTokens(AccountId accountId, VerificationPurpose purpose, Instant now) {
        return jpaRepository.invalidateActiveTokens(accountId.value(), purpose.name(), now);
    }

    @Override
    public int consumeIfActive(String tokenHash, Instant now) {
        return jpaRepository.consumeIfActive(tokenHash, now);
    }

    private VerificationToken toDomain(VerificationTokenEntity entity) {
        return VerificationToken.reconstitute(
                entity.getId(),
                new AccountId(entity.getAccountId()),
                entity.getTokenHash(),
                VerificationPurpose.valueOf(entity.getPurpose()),
                entity.getExpiresAt(),
                entity.getConsumedAt());
    }
}
