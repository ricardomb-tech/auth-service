package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.application.usecase.RegisterAccountResult;
import com.auth_service.auth.application.usecase.RegisterAccountUseCase;
import com.auth_service.auth.application.usecase.ResendVerificationUseCase;
import com.auth_service.auth.application.usecase.VerifyAccountUseCase;
import com.auth_service.auth.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterAccountUseCase registerAccountUseCase;

    @MockitoBean
    private VerifyAccountUseCase verifyAccountUseCase;

    @MockitoBean
    private ResendVerificationUseCase resendVerificationUseCase;

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
}
