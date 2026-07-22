package com.auth_service.auth.application.usecase;

import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.exception.DomainValidationException;
import com.auth_service.auth.domain.exception.SelfManagementNotAllowedException;
import com.auth_service.auth.domain.exception.TargetAccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AdminAction;
import com.auth_service.auth.domain.model.AuditLogEntry;
import com.auth_service.auth.domain.model.AuditResult;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.AuditLogRepository;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FR-11/FR-13 — único caso de uso que muta Cuentas ajenas por vía
 * administrativa (AD-6, AD-13) y el único que escribe {@code audit_log}
 * (AD-20). {@code AdminController} traduce sus resultados a HTTP, igual
 * que el resto de controllers del proyecto.
 */
@Service
public class ManageAccountUseCase {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public ManageAccountUseCase(AccountRepository accountRepository, RefreshTokenRepository refreshTokenRepository,
                                 AuditLogRepository auditLogRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    public record AccountPage(List<Account> content, int page, int size, long totalElements) {
    }

    @Transactional(readOnly = true)
    public AccountPage listAccounts(int page, int size) {
        if (page < 0) {
            throw new DomainValidationException("page debe ser mayor o igual que 0.");
        }
        if (size < 1 || size > 100) {
            throw new DomainValidationException("size debe estar entre 1 y 100.");
        }
        return new AccountPage(accountRepository.findAllPaged(page, size), page, size, accountRepository.countAll());
    }

    @Transactional(readOnly = true)
    public Account getAccountDetail(String targetAccountId) {
        return findTarget(targetAccountId);
    }

    /**
     * AC #4 / auto-protección: el chequeo compara actor contra target ANTES
     * de invocar {@code target.disable()} — si son la misma Cuenta, se
     * audita el intento rechazado y se lanza sin tocar el estado de la
     * Cuenta ni las Familias de Refresh Tokens.
     *
     * <p>{@code noRollbackFor}: sin esto, la fila de auditoría del intento
     * rechazado (insertada ANTES de lanzar) se revertiría junto con el
     * resto de la transacción al escapar {@link SelfManagementNotAllowedException}
     * — el mismo gotcha que {@code LoginUseCase}/{@code RefreshTokenUseCase}
     * ya documentan para sus propios casos. Si en cambio {@code Account.disable()}
     * lanza {@link DomainValidationException} (Cuenta ya DISABLED), NO hace
     * falta preservar nada: ese chequeo ocurre antes de cualquier escritura,
     * así que el rollback por defecto es correcto ahí.</p>
     */
    @Transactional(noRollbackFor = SelfManagementNotAllowedException.class)
    public Account disableAccount(String actorSubject, String targetAccountId) {
        AccountId actorId = parseActorId(actorSubject);
        Account target = findTarget(targetAccountId);
        Instant now = Instant.now(clock);

        if (actorId.equals(target.id())) {
            auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.DISABLE_ACCOUNT, AuditResult.REJECTED_SELF, now));
            throw new SelfManagementNotAllowedException("Un Administrador no puede desactivarse a sí mismo.");
        }

        target.disable();
        // revokeAllForAccount ANTES de save() (mismo orden que ResetPasswordUseCase,
        // Story 3.1): es un @Modifying(clearAutomatically = true) que limpia el
        // contexto de persistencia — si corriera después del save(), el UPDATE de
        // Hibernate aún sin flush se perdería en silencio.
        refreshTokenRepository.revokeAllForAccount(target.id(), now);
        accountRepository.save(target);
        auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.DISABLE_ACCOUNT, AuditResult.SUCCESS, now));
        return target;
    }

    /** Ver Javadoc de {@link #disableAccount} — mismo motivo para {@code noRollbackFor} y la misma auto-protección (AC #4). */
    @Transactional(noRollbackFor = SelfManagementNotAllowedException.class)
    public Account reactivateAccount(String actorSubject, String targetAccountId) {
        AccountId actorId = parseActorId(actorSubject);
        Account target = findTarget(targetAccountId);
        Instant now = Instant.now(clock);

        if (actorId.equals(target.id())) {
            auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.REACTIVATE_ACCOUNT, AuditResult.REJECTED_SELF, now));
            throw new SelfManagementNotAllowedException("Un Administrador no puede reactivarse a sí mismo.");
        }

        target.reactivate();
        accountRepository.save(target);
        auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.REACTIVATE_ACCOUNT, AuditResult.SUCCESS, now));
        return target;
    }

    /** Ver Javadoc de {@link #disableAccount} — mismo motivo para {@code noRollbackFor}. */
    @Transactional(noRollbackFor = SelfManagementNotAllowedException.class)
    public Account updateRoles(String actorSubject, String targetAccountId, Set<String> rawRoles) {
        AccountId actorId = parseActorId(actorSubject);
        Account target = findTarget(targetAccountId);
        Set<Role> newRoles = parseRoles(rawRoles);
        Instant now = Instant.now(clock);

        if (actorId.equals(target.id()) && !newRoles.contains(Role.ADMIN)) {
            auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.UPDATE_ROLES, AuditResult.REJECTED_SELF, now));
            throw new SelfManagementNotAllowedException("Un Administrador no puede quitarse el Rol ADMIN a sí mismo.");
        }

        target.updateRoles(newRoles);
        accountRepository.save(target);
        auditLogRepository.save(AuditLogEntry.create(actorId, target.id(), AdminAction.UPDATE_ROLES, AuditResult.SUCCESS, now));
        return target;
    }

    // Mismo patrón defensivo que GetOwnProfileUseCase (Story 1.7): un sub de
    // Access Token que no es UUID no debe terminar en el catch-all 500.
    private AccountId parseActorId(String rawSubject) {
        try {
            return new AccountId(UUID.fromString(rawSubject));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new AccountNotFoundException("Claim sub del Access Token ausente o no es un UUID.");
        }
    }

    // Un id de Cuenta objetivo malformado en la URL es, desde la perspectiva
    // del llamador, indistinguible de "no existe" — no se le da un 400
    // distinto de formato vs. existencia (mismo principio de no distinguir
    // motivos ya aplicado en otros flujos de esta app, aunque aquí no hay
    // enumeración en juego: es simplicidad de contrato, no anti-enumeración).
    private Account findTarget(String rawTargetId) {
        AccountId targetId;
        try {
            targetId = new AccountId(UUID.fromString(rawTargetId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new TargetAccountNotFoundException("Ninguna Cuenta corresponde al id indicado.");
        }
        return accountRepository.findById(targetId)
                .orElseThrow(() -> new TargetAccountNotFoundException("Ninguna Cuenta corresponde al id indicado."));
    }

    private Set<Role> parseRoles(Set<String> rawRoles) {
        if (rawRoles == null || rawRoles.isEmpty()) {
            throw new DomainValidationException("roles no puede estar vacío.");
        }
        try {
            return rawRoles.stream()
                    .map(role -> {
                        if (role == null) {
                            throw new DomainValidationException("roles contiene un valor no reconocido.");
                        }
                        return role.trim().toUpperCase();
                    })
                    .map(Role::valueOf)
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException invalidRole) {
            throw new DomainValidationException("roles contiene un valor no reconocido.");
        }
    }
}
