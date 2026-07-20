package com.auth_service.auth.infrastructure.adapters.postgresql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    // clearAutomatically: un @Modifying @Query es un UPDATE masivo que no pasa
    // por el ciclo de vida normal de JPA — sin esto, una entidad ya cargada en
    // la misma transacción queda con datos obsoletos en el contexto de
    // persistencia aunque la fila en BD ya haya cambiado. El WHERE con
    // used_at IS NULL AND revoked_at IS NULL (no solo used_at) cierra tanto la
    // carrera de dos canjes concurrentes del mismo token como la carrera con
    // una revocación de familia disparada por un hermano justo antes.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.usedAt = :usedAt WHERE r.id = :id AND r.usedAt IS NULL AND r.revokedAt IS NULL")
    int markUsedIfUnused(@Param("id") UUID id, @Param("usedAt") Instant usedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :revokedAt WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("revokedAt") Instant revokedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshTokenEntity r SET r.revokedAt = :revokedAt WHERE r.accountId = :accountId AND r.revokedAt IS NULL")
    int revokeAllForAccount(@Param("accountId") UUID accountId, @Param("revokedAt") Instant revokedAt);
}
