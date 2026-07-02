package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1..#4 de la
 * Story 1.3. Los tokens se emiten directamente vía {@link VerificationToken#issue}
 * y se persisten con los repositorios reales (no vía {@code POST /auth/register})
 * porque el {@code rawToken} solo existe en memoria en el instante de emisión —
 * bajo {@code @Transactional} el listener AFTER_COMMIT nunca dispara (mismo
 * límite aceptado que en {@code AuthControllerIntegrationTest} de la Story 1.2),
 * así que pasar por el endpoint de registro no daría acceso al token real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AuthVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private Clock clock;

    @Test
    void validTokenActivatesAccountAndCannotBeReused() throws Exception {
        Account account = accountRepository.save(
                Account.register(new Email("verifica@example.com"), new HashedPassword("bcrypt-hash")));
        VerificationToken.Issued issued = VerificationToken.issue(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        verificationTokenRepository.save(issued.token());

        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\"}"))
                .andExpect(status().isOk());

        Account reloaded = accountRepository.findById(account.id()).orElseThrow();
        assertThat(reloaded.status().name()).isEqualTo("ACTIVE");

        // Reutilizar el mismo token ya consumido falla.
        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void nonExistentTokenReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token-que-nunca-existio\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void resendOnPendingAccountInvalidatesOldTokenAndReturns202() throws Exception {
        Account account = accountRepository.save(
                Account.register(new Email("reenvio@example.com"), new HashedPassword("bcrypt-hash")));
        VerificationToken.Issued oldIssued = VerificationToken.issue(
                account.id(), VerificationPurpose.EMAIL_VERIFICATION, Duration.ofHours(24), clock);
        verificationTokenRepository.save(oldIssued.token());

        mockMvc.perform(post("/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reenvio@example.com\"}"))
                .andExpect(status().isAccepted());

        // El token viejo quedó invalidado por la reemisión — ya no sirve.
        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + oldIssued.rawToken() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendWithNonExistentEmailReturnsSameGenericResponse() throws Exception {
        mockMvc.perform(post("/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\"}"))
                .andExpect(status().isAccepted());
    }
}
