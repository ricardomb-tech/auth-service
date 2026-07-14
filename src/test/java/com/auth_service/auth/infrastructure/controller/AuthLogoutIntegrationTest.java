package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
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

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1, #2, #4 de la
 * Story 1.6. El Refresh Token de partida se obtiene vía login real
 * ({@code POST /auth/login}) — mismo patrón que {@code AuthRefreshIntegrationTest}
 * (Story 1.5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthLogoutIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void persistActiveAccount(String email) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        accountRepository.save(account);
    }

    private String loginAndGetRefreshToken(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asText();
    }

    @Test
    void logoutOfAValidTokenReturns204AndRevokesItsFamilySoThatRefreshFailsAfterwards() throws Exception {
        persistActiveAccount("logout@example.com");
        String rawToken = loginAndGetRefreshToken("logout@example.com");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // Verificación leyendo la fila real de la base de datos tras la petición.
        RefreshToken reloaded = refreshTokenRepository.findByTokenHash(RefreshToken.hashRawToken(rawToken)).orElseThrow();
        assertThat(reloaded.revokedAt()).isNotNull();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void logoutOfAnUnrecognizedTokenReturns204WithoutError() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"un-token-que-nunca-existio\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void logoutRevokesTheWholeFamilyNotJustThePresentedToken() throws Exception {
        // AC #2: "cualquier otro miembro vivo de la misma Familia" también debe
        // fallar tras el logout, no solo el token presentado.
        persistActiveAccount("familia@example.com");
        String originalRawToken = loginAndGetRefreshToken("familia@example.com");

        // Rotar una vez para generar un segundo token vivo en la misma Familia.
        String rotateBody = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String siblingRawToken = objectMapper.readTree(rotateBody).get("refreshToken").asText();

        // Logout con el token original (ya usado/rotado, pero identifica la misma Familia).
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRawToken + "\"}"))
                .andExpect(status().isNoContent());

        // El hermano vivo (el que sí quedó sin usar tras la rotación) también debe fallar.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + siblingRawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        RefreshToken siblingReloaded = refreshTokenRepository.findByTokenHash(RefreshToken.hashRawToken(siblingRawToken)).orElseThrow();
        assertThat(siblingReloaded.revokedAt()).isNotNull();
    }

    @Test
    void loggingOutTwiceWithTheSameTokenReturns204BothTimes() throws Exception {
        persistActiveAccount("doble-logout@example.com");
        String rawToken = loginAndGetRefreshToken("doble-logout@example.com");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isNoContent());
    }
}
