package com.auth_service.auth.infrastructure.adapters.postgresql;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.port.AuditLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository jpaRepository;

    public AuditLogRepositoryAdapter(AuditLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(AuditLogEntry entry) {
        jpaRepository.save(new AuditLogEntity(entry.id(), entry.actorAccountId().value(), entry.targetAccountId().value(),
                entry.action().name(), entry.result().name(), entry.occurredAt()));
    }

    @Override
    public List<AuditLogEntry> search(AccountId targetAccountId, Instant from, Instant to) {
        return jpaRepository.search(targetAccountId != null ? targetAccountId.value() : null, from, to).stream()
                .map(this::toDomain)
                .toList();
    }

    private AuditLogEntry toDomain(AuditLogEntity entity) {
        return new AuditLogEntry(entity.getId(), new AccountId(entity.getActorAccountId()),
                new AccountId(entity.getTargetAccountId()), AdminAction.valueOf(entity.getAction()),
                AuditResult.valueOf(entity.getResult()), entity.getOccurredAt());
    }
}
