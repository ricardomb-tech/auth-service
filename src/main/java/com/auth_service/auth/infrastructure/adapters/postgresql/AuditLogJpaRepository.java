package com.auth_service.auth.infrastructure.adapters.postgresql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {

    // Query nativa en vez de JPQL: cada aparición textual de un parámetro
    // nombrado se traduce a un placeholder "?" POSICIONAL independiente —
    // Postgres resuelve el tipo de cada uno por separado, así que un CAST en
    // una sola aparición no basta para las otras. Sin CAST en la posición
    // "? IS NULL" en particular, Postgres falla con "could not determine
    // data type of parameter $N" porque esa posición no da ninguna pista de
    // tipo por sí sola — hay que castear las DOS apariciones de cada parámetro.
    @Query(value = "SELECT id, actor_account_id, target_account_id, action, result, occurred_at FROM audit_log WHERE "
            + "(CAST(:targetAccountId AS uuid) IS NULL OR target_account_id = CAST(:targetAccountId AS uuid)) AND "
            + "(CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz)) AND "
            + "(CAST(:to AS timestamptz) IS NULL OR occurred_at <= CAST(:to AS timestamptz)) "
            + "ORDER BY occurred_at DESC", nativeQuery = true)
    List<AuditLogEntity> search(@Param("targetAccountId") UUID targetAccountId,
                                 @Param("from") Instant from, @Param("to") Instant to);
}
