package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.LoginCommand;
import com.auth_service.auth.application.usecase.LoginUseCase;
import com.auth_service.auth.application.usecase.LogoutCommand;
import com.auth_service.auth.application.usecase.LogoutUseCase;
import com.auth_service.auth.application.usecase.OAuth2ExchangeCommand;
import com.auth_service.auth.application.usecase.OAuth2ExchangeUseCase;
import com.auth_service.auth.application.usecase.RefreshCommand;
import com.auth_service.auth.application.usecase.RefreshTokenUseCase;
import com.auth_service.auth.application.usecase.RegisterAccountCommand;
import com.auth_service.auth.application.usecase.RegisterAccountUseCase;
import com.auth_service.auth.application.usecase.ResendVerificationCommand;
import com.auth_service.auth.application.usecase.ResendVerificationUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.application.usecase.VerifyAccountUseCase;
import com.auth_service.auth.application.usecase.VerifyCommand;
import com.auth_service.auth.infrastructure.controller.dto.LoginRequest;
import com.auth_service.auth.infrastructure.controller.dto.LoginResponse;
import com.auth_service.auth.infrastructure.controller.dto.LogoutRequest;
import com.auth_service.auth.infrastructure.controller.dto.MessageResponse;
import com.auth_service.auth.infrastructure.controller.dto.OAuth2ExchangeRequest;
import com.auth_service.auth.infrastructure.controller.dto.RefreshRequest;
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
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;
    private final OAuth2ExchangeUseCase oAuth2ExchangeUseCase;

    public AuthController(RegisterAccountUseCase registerAccountUseCase,
                           VerifyAccountUseCase verifyAccountUseCase,
                           ResendVerificationUseCase resendVerificationUseCase,
                           LoginUseCase loginUseCase,
                           RefreshTokenUseCase refreshTokenUseCase,
                           LogoutUseCase logoutUseCase,
                           OAuth2ExchangeUseCase oAuth2ExchangeUseCase) {
        this.registerAccountUseCase = registerAccountUseCase;
        this.verifyAccountUseCase = verifyAccountUseCase;
        this.resendVerificationUseCase = resendVerificationUseCase;
        this.loginUseCase = loginUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.logoutUseCase = logoutUseCase;
        this.oAuth2ExchangeUseCase = oAuth2ExchangeUseCase;
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
        verifyAccountUseCase.verify(new VerifyCommand(request.token()));
        return ResponseEntity.ok(VERIFY_RESPONSE);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        // El resultado se ignora deliberadamente — misma respuesta genérica
        // exista o no la Cuenta, y también si ya no aplica (AC #4).
        resendVerificationUseCase.resend(new ResendVerificationCommand(request.email()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RESEND_VERIFICATION_RESPONSE);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // AuthenticationFailedException se deja propagar hacia GlobalExceptionHandler
        // (401 problem+json genérico) — a diferencia de /register, aquí no hay una
        // carrera real que capturar.
        TokenIssuer.IssuedTokens tokens = loginUseCase.login(new LoginCommand(request.email(), request.password()));
        LoginResponse response = new LoginResponse(
                tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.accessTokenExpiresInSeconds());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        // InvalidRefreshTokenException se deja propagar hacia GlobalExceptionHandler
        // (401 problem+json genérico) — mismo patrón que /login, el controller no captura nada.
        TokenIssuer.IssuedTokens tokens = refreshTokenUseCase.refresh(new RefreshCommand(request.refreshToken()));
        LoginResponse response = new LoginResponse(
                tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.accessTokenExpiresInSeconds());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        // Idempotente por diseño (AC #4 de la Story 1.6) — el caso de uso nunca
        // lanza, así que no hay nada que capturar aquí.
        logoutUseCase.logout(new LogoutCommand(request.refreshToken()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/oauth2/exchange")
    public ResponseEntity<LoginResponse> exchangeOAuth2Code(@Valid @RequestBody OAuth2ExchangeRequest request) {
        // Canjea el código de un solo uso emitido por OAuth2AuthenticationSuccessHandler
        // tras un login federado (Story 2.1, revisión de seguridad) — evita que
        // Access+Refresh Token viajen en la URL de la redirección de éxito.
        // OAuth2ExchangeFailedException se deja propagar hacia GlobalExceptionHandler
        // (401 problem+json genérico), mismo patrón que /login y /refresh.
        TokenIssuer.IssuedTokens tokens = oAuth2ExchangeUseCase.exchange(new OAuth2ExchangeCommand(request.code()));
        LoginResponse response = new LoginResponse(
                tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.accessTokenExpiresInSeconds());
        return ResponseEntity.ok(response);
    }
}
