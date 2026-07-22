package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.domain.port.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** FR-13 (Story 4.3) — lado de lectura de {@code audit_log}; el de escritura vive en {@link ManageAccountUseCase}. */
@Service
public class QueryAuditLogUseCase {

    private final AuditLogRepository auditLogRepository;

    public QueryAuditLogUseCase(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogEntry> search(String rawAccountId, String rawFrom, String rawTo) {
        AccountId targetAccountId = parseOptionalAccountId(rawAccountId);
        Instant from = parseOptionalInstant(rawFrom, "from");
        Instant to = parseOptionalInstant(rawTo, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainValidationException("from no puede ser posterior a to.");
        }
        return auditLogRepository.search(targetAccountId, from, to);
    }

    private AccountId parseOptionalAccountId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new AccountId(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw new DomainValidationException("accountId no es un UUID válido.");
        }
    }

    private Instant parseOptionalInstant(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException malformed) {
            throw new DomainValidationException(paramName + " no es una fecha ISO-8601 válida.");
        }
    }
}
