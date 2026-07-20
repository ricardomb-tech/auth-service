package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.model.VerificationPurpose;
import com.auth_service.auth.domain.model.VerificationToken;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.VerificationTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #3/#4/#5 de la
 * Story 3.1. Mismo patrón que {@code AuthLoginIntegrationTest}/{@code
 * AuthVerificationIntegrationTest}: la Cuenta se persiste directamente vía
 * {@link AccountRepository}/{@link PasswordHasher}, y el token de
 * recuperación se emite directamente vía {@link VerificationToken#issue}
 * (el {@code rawToken} solo existe en memoria en el instante de emisión).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthResetPasswordIntegrationTest {

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
    private PasswordHasher passwordHasher;

    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AccountId persistAccount(String email, String rawPassword) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword(rawPassword));
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        return accountRepository.save(account).id();
    }

    private String login(String email, String rawPassword) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + rawPassword + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("refreshToken").asText();
    }

    @Test
    void validTokenAndPasswordUpdatesPasswordAndRevokesPreExistingRefreshTokens() throws Exception {
        AccountId accountId = persistAccount("restablece@example.com", "OldPass1");
        String refreshTokenBeforeReset = login("restablece@example.com", "OldPass1");

        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        verificationTokenRepository.save(issued.token());

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"newPassword\":\"NuevaPass1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"restablece@example.com\",\"password\":\"OldPass1\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"restablece@example.com\",\"password\":\"NuevaPass1\"}"))
                .andExpect(status().isOk());

        // El Refresh Token emitido ANTES del reset ya no puede canjearse — todas
        // las Familias de la Cuenta quedaron revocadas (AC #3, AD-4).
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshTokenBeforeReset + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonExistentTokenReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token-que-nunca-existio\",\"newPassword\":\"NuevaPass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void reusingTheSameTokenTwiceFailsTheSecondTime() throws Exception {
        AccountId accountId = persistAccount("reintento@example.com", "OldPass1");
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        verificationTokenRepository.save(issued.token());

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"newPassword\":\"NuevaPass1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"newPassword\":\"OtraPass2\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidNewPasswordReturns400AndTheSameTokenStillWorksAfterwards() throws Exception {
        AccountId accountId = persistAccount("politica@example.com", "OldPass1");
        VerificationToken.Issued issued = VerificationToken.issue(
                accountId, VerificationPurpose.PASSWORD_RESET, Duration.ofHours(1), clock);
        verificationTokenRepository.save(issued.token());

        // "short" incumple la política mínima (≥8 caracteres) — el token no se consume.
        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + issued.rawToken() + "\",\"newPassword\":\"NuevaPass1\"}"))
                .andExpect(status().isOk());
    }
}
