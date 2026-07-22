package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.port.AuditLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryAuditLogUseCaseTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final QueryAuditLogUseCase useCase = new QueryAuditLogUseCase(auditLogRepository);

    private AuditLogEntry sampleEntry() {
        return AuditLogEntry.create(AccountId.newId(), AccountId.newId(), AdminAction.DISABLE_ACCOUNT,
                AuditResult.SUCCESS, Instant.now());
    }

    @Test
    void searchWithNoFiltersReturnsAllEntriesFromRepository() {
        List<AuditLogEntry> entries = List.of(sampleEntry(), sampleEntry());
        when(auditLogRepository.search(isNull(), isNull(), isNull())).thenReturn(entries);

        List<AuditLogEntry> result = useCase.search(null, null, null);

        assertThat(result).isEqualTo(entries);
    }

    @Test
    void searchWithAccountIdFilterParsesAndDelegatesToRepository() {
        AccountId targetId = AccountId.newId();
        when(auditLogRepository.search(eq(targetId), isNull(), isNull())).thenReturn(List.of());

        useCase.search(targetId.value().toString(), null, null);

        verify(auditLogRepository).search(eq(targetId), isNull(), isNull());
    }

    @Test
    void searchWithMalformedAccountIdThrowsDomainValidationException() {
        assertThatThrownBy(() -> useCase.search("no-es-un-uuid", null, null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void searchWithFromAndToParsesAndDelegatesToRepository() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-31T23:59:59Z");
        when(auditLogRepository.search(isNull(), eq(from), eq(to))).thenReturn(List.of());

        useCase.search(null, from.toString(), to.toString());

        verify(auditLogRepository).search(isNull(), eq(from), eq(to));
    }

    @Test
    void searchWithMalformedFromThrowsDomainValidationException() {
        assertThatThrownBy(() -> useCase.search(null, "2026-07-01", null))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void searchWithFromAfterToThrowsDomainValidationException() {
        Instant from = Instant.parse("2026-07-31T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        assertThatThrownBy(() -> useCase.search(null, from.toString(), to.toString()))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void searchWithBlankOptionalParamsTreatsThemAsAbsent() {
        when(auditLogRepository.search(isNull(), isNull(), isNull())).thenReturn(List.of());

        useCase.search("", "", "");

        verify(auditLogRepository).search(isNull(), isNull(), isNull());
    }
}
