package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.Role;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1/#2 de la
 * Story 3.1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthForgotPasswordIntegrationTest {

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

    private AccountId persistAccount(String email) {
        Account account = Account.reconstitute(AccountId.newId(), new Email(email),
                new HashedPassword("bcrypt-hash"), AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        return accountRepository.save(account).id();
    }

    @Test
    void existingAccountReturns202AndPersistsANewPasswordResetToken() throws Exception {
        persistAccount("recupera@example.com");

        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"recupera@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void nonExistentEmailReturnsSameGenericResponse() throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void secondRequestInvalidatesThePreviousPasswordResetToken() throws Exception {
        AccountId accountId = persistAccount("segunda-solicitud@example.com");
        VerificationToken.Issued oldIssued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        verificationTokenRepository.save(oldIssued.token());

        mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"segunda-solicitud@example.com\"}"))
                .andExpect(status().isAccepted());

        // El token viejo quedó invalidado por la nueva solicitud — reset-password con él falla.
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + oldIssued.rawToken() + "\",\"newPassword\":\"NuevaPass1\"}"))
                .andExpect(status().isBadRequest());

        assertThat(verificationTokenRepository.findByTokenHash(oldIssued.token().tokenHash()))
                .hasValueSatisfying(token -> assertThat(token.consumedAt()).isNotNull());
    }
}
