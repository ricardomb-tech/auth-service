package com.auth_service.auth.infrastructure.adapters.postgresql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenJpaRepository extends JpaRepository<VerificationTokenEntity, UUID> {

    Optional<VerificationTokenEntity> findByTokenHash(String tokenHash);

    // clearAutomatically: un @Modifying @Query es un UPDATE masivo que no pasa
    // por el ciclo de vida normal de JPA — sin esto, una entidad ya cargada en
    // la misma transacción queda con datos obsoletos en el contexto de
    // persistencia aunque la fila en BD ya haya cambiado.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VerificationTokenEntity t SET t.consumedAt = :now "
            + "WHERE t.accountId = :accountId AND t.purpose = :purpose AND t.consumedAt IS NULL")
    int invalidateActiveTokens(@Param("accountId") UUID accountId, @Param("purpose") String purpose, @Param("now") Instant now);
}
