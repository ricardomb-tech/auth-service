package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.RegisterAccountCommand;
import com.auth_service.auth.application.usecase.RegisterAccountUseCase;
import com.auth_service.auth.application.usecase.ResendVerificationUseCase;
import com.auth_service.auth.application.usecase.VerifyAccountUseCase;
import com.auth_service.auth.infrastructure.controller.dto.MessageResponse;
import com.auth_service.auth.infrastructure.controller.dto.RegisterRequest;
import com.auth_service.auth.infrastructure.controller.dto.ResendVerificationRequest;
import com.auth_service.auth.infrastructure.controller.dto.VerifyRequest;
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

    private static final MessageResponse VERIFY_RESPONSE =
            new MessageResponse("Tu cuenta ha sido verificada. Ya puedes iniciar sesión.");

    private static final MessageResponse RESEND_VERIFICATION_RESPONSE =
            new MessageResponse("Si el email es válido y está pendiente de verificación, recibirás un correo con un nuevo enlace.");

    private final RegisterAccountUseCase registerAccountUseCase;
    private final VerifyAccountUseCase verifyAccountUseCase;
    private final ResendVerificationUseCase resendVerificationUseCase;

    public AuthController(RegisterAccountUseCase registerAccountUseCase,
                           VerifyAccountUseCase verifyAccountUseCase,
                           ResendVerificationUseCase resendVerificationUseCase) {
        this.registerAccountUseCase = registerAccountUseCase;
        this.verifyAccountUseCase = verifyAccountUseCase;
        this.resendVerificationUseCase = resendVerificationUseCase;
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

    @PostMapping("/verify")
    public ResponseEntity<MessageResponse> verify(@Valid @RequestBody VerifyRequest request) {
        // A diferencia del registro, aquí el error SÍ es distinguible del éxito
        // (DomainValidationException propaga a GlobalExceptionHandler → 400
        // problem+json) — el usuario ya posee el token, no hay enumeración en juego.
        verifyAccountUseCase.verify(request.token());
        return ResponseEntity.ok(VERIFY_RESPONSE);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        // El resultado se ignora deliberadamente — misma respuesta genérica
        // exista o no la Cuenta, y también si ya no aplica (AC #4).
        resendVerificationUseCase.resend(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RESEND_VERIFICATION_RESPONSE);
    }
}
