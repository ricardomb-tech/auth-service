package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.RegisterAccountCommand;
import com.auth_service.auth.application.usecase.RegisterAccountUseCase;
import com.auth_service.auth.infrastructure.controller.dto.MessageResponse;
import com.auth_service.auth.infrastructure.controller.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final MessageResponse REGISTER_RESPONSE =
            new MessageResponse("Si el email es válido, recibirás un correo de verificación.");

    private final RegisterAccountUseCase registerAccountUseCase;

    public AuthController(RegisterAccountUseCase registerAccountUseCase) {
        this.registerAccountUseCase = registerAccountUseCase;
    }

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        try {
            // El resultado del caso de uso se ignora deliberadamente: la respuesta es
            // idéntica exista o no la Cuenta (anti-enumeración, AC #2 de la Story 1.2).
            registerAccountUseCase.register(new RegisterAccountCommand(request.email(), request.password()));
        } catch (DataIntegrityViolationException raceLoser) {
            // Dos registros concurrentes con el mismo email: el "perdedor" de la
            // carrera choca contra el UNIQUE de accounts.email. Se trata igual que
            // "ya registrado" — la respuesta sigue siendo indistinguible (AC #2).
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(REGISTER_RESPONSE);
    }
}
