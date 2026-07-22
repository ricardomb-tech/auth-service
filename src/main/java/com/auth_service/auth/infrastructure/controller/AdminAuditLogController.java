package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.QueryAuditLogUseCase;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.infrastructure.controller.dto.AuditLogEntryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** FR-13 (Story 4.3) — lado de lectura de la auditoría; exige Rol ADMIN (AD-11, AD-18), igual que {@code AdminController}. */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditLogController {

    private final QueryAuditLogUseCase queryAuditLogUseCase;

    public AdminAuditLogController(QueryAuditLogUseCase queryAuditLogUseCase) {
        this.queryAuditLogUseCase = queryAuditLogUseCase;
    }

    @GetMapping
    public ResponseEntity<List<AuditLogEntryResponse>> search(@RequestParam(required = false) String accountId,
                                                                 @RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to) {
        List<AuditLogEntryResponse> entries = queryAuditLogUseCase.search(accountId, from, to).stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(entries);
    }

    private AuditLogEntryResponse toResponse(AuditLogEntry entry) {
        return new AuditLogEntryResponse(entry.id().toString(), entry.actorAccountId().value().toString(),
                entry.targetAccountId().value().toString(), entry.action().name(), entry.result().name(),
                entry.occurredAt());
    }
}
