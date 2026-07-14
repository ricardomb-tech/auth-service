package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.LoginUseCase;
import com.auth_service.auth.application.usecase.LogoutCommand;
import com.auth_service.auth.application.usecase.LogoutUseCase;
import com.auth_service.auth.application.usecase.RefreshTokenUseCase;
import com.auth_service.auth.application.usecase.RegisterAccountResult;
import com.auth_service.auth.application.usecase.RegisterAccountUseCase;
import com.auth_service.auth.application.usecase.ResendVerificationUseCase;
import com.auth_service.auth.application.usecase.TokenIssuer;
import com.auth_service.auth.application.usecase.VerifyAccountUseCase;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.config.SecurityConfig;
import com.auth_service.auth.domain.exception.AuthenticationFailedException;
import com.auth_service.auth.domain.exception.InvalidRefreshTokenException;
import com.auth_service.auth.infrastructure.adapters.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test con {@link RegisterAccountUseCase} mockeado — confirma que el
 * controller absorbe la carrera de registro concurrente (patch de code
 * review) sin exponerla al cliente. {@code @Import(SecurityConfig.class)}:
 * sin esto, {@code @WebMvcTest} cae al deny-all por defecto de Spring
 * Security (con CSRF activo), no al nuestro (mismo ajuste que
 * {@code SecurityConfigTest} de la Story 1.1).
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterAccountUseCase registerAccountUseCase;

    @MockitoBean
    private VerifyAccountUseCase verifyAccountUseCase;

    @MockitoBean
    private ResendVerificationUseCase resendVerificationUseCase;

    @MockitoBean
    private LoginUseCase loginUseCase;

    @MockitoBean
    private RefreshTokenUseCase refreshTokenUseCase;

    @MockitoBean
    private LogoutUseCase logoutUseCase;

    @Test
    void concurrentDuplicateRegistrationStillReturns202() throws Exception {
        when(registerAccountUseCase.register(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"visitante@example.com\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void successfulRegistrationReturns202() throws Exception {
        when(registerAccountUseCase.register(any())).thenReturn(RegisterAccountResult.ACCOUNT_CREATED);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"visitante@example.com\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void successfulLoginReturns200WithTokens() throws Exception {
        when(loginUseCase.login(any())).thenReturn(new TokenIssuer.IssuedTokens("access-token", "refresh-token", 900L));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"titular@example.com\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void loginWithInvalidCredentialsReturns401ProblemJson() throws Exception {
        when(loginUseCase.login(any())).thenThrow(new AuthenticationFailedException("Cuenta inexistente."));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void successfulRefreshReturns200WithNewTokens() throws Exception {
        when(refreshTokenUseCase.refresh(any())).thenReturn(new TokenIssuer.IssuedTokens("new-access-token", "new-refresh-token", 900L));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"some-raw-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void refreshWithInvalidTokenReturns401ProblemJson() throws Exception {
        when(refreshTokenUseCase.refresh(any())).thenThrow(new InvalidRefreshTokenException("Refresh token inválido o expirado."));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"some-raw-refresh-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void refreshWithBlankTokenReturns400() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutReturns204WithNoBodyAndInvokesUseCase() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"some-raw-refresh-token\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(logoutUseCase).logout(eq(new LogoutCommand("some-raw-refresh-token")));
    }

    @Test
    void logoutWithBlankTokenReturns400() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(logoutUseCase, never()).logout(any());
    }
}
