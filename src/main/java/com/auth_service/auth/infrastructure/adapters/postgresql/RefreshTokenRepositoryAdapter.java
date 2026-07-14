package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        RefreshTokenEntity entity = new RefreshTokenEntity(
                refreshToken.id(),
                refreshToken.accountId().value(),
                refreshToken.tokenHash(),
                refreshToken.familyId(),
                refreshToken.expiresAt(),
                refreshToken.usedAt(),
                refreshToken.revokedAt());
        jpaRepository.save(entity);
        return refreshToken;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    public int markUsedIfUnused(UUID tokenId, Instant usedAt) {
        return jpaRepository.markUsedIfUnused(tokenId, usedAt);
    }

    @Override
    public int revokeFamily(UUID familyId, Instant revokedAt) {
        return jpaRepository.revokeFamily(familyId, revokedAt);
    }

    private RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.reconstitute(
                entity.getId(),
                new AccountId(entity.getAccountId()),
                entity.getTokenHash(),
                entity.getFamilyId(),
                entity.getExpiresAt(),
                entity.getUsedAt(),
                entity.getRevokedAt());
    }
}
