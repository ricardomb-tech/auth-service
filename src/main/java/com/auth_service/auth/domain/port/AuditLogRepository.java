package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AuditLogEntry;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository {

    void save(AuditLogEntry entry);

    List<AuditLogEntry> search(AccountId targetAccountId, Instant from, Instant to);
}
