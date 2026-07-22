package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.ManageAccountUseCase;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.infrastructure.controller.dto.AdminAccountResponse;
import com.auth_service.auth.infrastructure.controller.dto.PagedAccountsResponse;
import com.auth_service.auth.infrastructure.controller.dto.UpdateRolesRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/** FR-11/FR-13 — todos los endpoints exigen Rol ADMIN (AD-11, AD-18). */
@RestController
@RequestMapping("/api/v1/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final ManageAccountUseCase manageAccountUseCase;

    public AdminController(ManageAccountUseCase manageAccountUseCase) {
        this.manageAccountUseCase = manageAccountUseCase;
    }

    @GetMapping
    public ResponseEntity<PagedAccountsResponse> list(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        ManageAccountUseCase.AccountPage result = manageAccountUseCase.listAccounts(page, size);
        List<AdminAccountResponse> content = result.content().stream().map(this::toResponse).toList();
        return ResponseEntity.ok(new PagedAccountsResponse(content, result.page(), result.size(), result.totalElements()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminAccountResponse> detail(@PathVariable String id) {
        return ResponseEntity.ok(toResponse(manageAccountUseCase.getAccountDetail(id)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<AdminAccountResponse> disable(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(toResponse(manageAccountUseCase.disableAccount(authentication.getName(), id)));
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<AdminAccountResponse> reactivate(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(toResponse(manageAccountUseCase.reactivateAccount(authentication.getName(), id)));
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<AdminAccountResponse> updateRoles(@PathVariable String id, @RequestBody UpdateRolesRequest request,
                                                              Authentication authentication) {
        return ResponseEntity.ok(toResponse(manageAccountUseCase.updateRoles(authentication.getName(), id, request.roles())));
    }

    private AdminAccountResponse toResponse(Account account) {
        return new AdminAccountResponse(account.id().value().toString(), account.email().value(),
                account.roles().stream().map(Role::name).collect(Collectors.toSet()), account.status().name(),
                account.createdAt());
    }
}
