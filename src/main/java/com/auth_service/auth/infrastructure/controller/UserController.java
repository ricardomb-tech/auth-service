package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.GetOwnProfileCommand;
import com.auth_service.auth.application.usecase.GetOwnProfileUseCase;
import com.auth_service.auth.domain.exception.AccountNotFoundException;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.infrastructure.controller.dto.ProfileResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final GetOwnProfileUseCase getOwnProfileUseCase;

    public UserController(GetOwnProfileUseCase getOwnProfileUseCase) {
        this.getOwnProfileUseCase = getOwnProfileUseCase;
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> me(Authentication authentication) {
        // Con el deny-all (AD-11) authentication nunca es null; el guard evita
        // que un refactor futuro de SecurityConfig que dejara pasar esta ruta
        // sin autenticar la convierta en un 500 por NPE en vez de un 401.
        if (authentication == null) {
            throw new AccountNotFoundException("Petición sin Authentication en el contexto de seguridad.");
        }
        // AccountNotFoundException se deja propagar hacia GlobalExceptionHandler
        // (401 problem+json) — mismo patrón que AuthController con las excepciones
        // de dominio de los demás flujos de autenticación.
        Account account = getOwnProfileUseCase.getOwnProfile(new GetOwnProfileCommand(authentication.getName()));
        return ResponseEntity.ok(toResponse(account));
    }

    private ProfileResponse toResponse(Account account) {
        return new ProfileResponse(
                account.id().value().toString(),
                account.email().value(),
                account.roles().stream().map(Role::name).collect(Collectors.toSet()),
                account.status().name(),
                List.of(),
                account.createdAt());
    }
}
