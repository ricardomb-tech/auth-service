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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1..#3 de la
 * Story 1.5. El Refresh Token de partida se obtiene vía login real
 * ({@code POST /auth/login}) — mismo patrón que {@code AuthLoginIntegrationTest}
 * (Story 1.4) — salvo en los casos de token expirado/no reconocido, donde se
 * persiste directamente vía {@link RefreshTokenRepository} porque necesitan
 * un {@code expiresAt} en el pasado o un hash sin fila correspondiente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthRefreshIntegrationTest {

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

    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Account persistActiveAccount(String email) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        return accountRepository.save(account);
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
    void refreshOfAValidUnusedTokenReturns200WithNewTokensAndMarksTheOriginalUsed() throws Exception {
        persistActiveAccount("rotacion@example.com");
        String originalRawToken = loginAndGetRefreshToken("rotacion@example.com");
        String originalHash = RefreshToken.hashRawToken(originalRawToken);
        UUID originalFamilyId = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow().familyId();

        String body = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        String newAccessToken = json.get("accessToken").asText();
        String newRawRefreshToken = json.get("refreshToken").asText();
        assertThat(newAccessToken).isNotBlank();
        assertThat(newRawRefreshToken).isNotEqualTo(originalRawToken);

        RefreshToken originalReloaded = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
        assertThat(originalReloaded.usedAt()).isNotNull();

        RefreshToken newToken = refreshTokenRepository.findByTokenHash(RefreshToken.hashRawToken(newRawRefreshToken)).orElseThrow();
        assertThat(newToken.familyId()).isEqualTo(originalFamilyId);
    }

    @Test
    void reusingAnAlreadyRotatedTokenRevokesTheWholeFamilyIncludingTheNewlyIssuedToken() throws Exception {
        persistActiveAccount("reuso@example.com");
        String originalRawToken = loginAndGetRefreshToken("reuso@example.com");

        String firstRefreshBody = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRawToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotatedRawToken = objectMapper.readTree(firstRefreshBody).get("refreshToken").asText();

        // Reintentar el token ya canjeado — señal de robo/reuso.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + originalRawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // Verificación leyendo la fila real de la base de datos tras la petición:
        // toda la familia queda revocada pese a la excepción (noRollbackFor).
        RefreshToken original = refreshTokenRepository.findByTokenHash(RefreshToken.hashRawToken(originalRawToken)).orElseThrow();
        RefreshToken rotated = refreshTokenRepository.findByTokenHash(RefreshToken.hashRawToken(rotatedRawToken)).orElseThrow();
        assertThat(original.revokedAt()).isNotNull();
        assertThat(rotated.revokedAt()).isNotNull();

        // Canje posterior de la fila nueva (ya revocada) también falla.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotatedRawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void expiredTokenReturns401ProblemJson() throws Exception {
        Account account = persistActiveAccount("expirado@example.com");
        String rawToken = "raw-expired-token";
        RefreshToken expiredToken = RefreshToken.reconstitute(UUID.randomUUID(), account.id(),
                RefreshToken.hashRawToken(rawToken), UUID.randomUUID(), Instant.now(clock).minus(Duration.ofSeconds(1)), null, null);
        refreshTokenRepository.save(expiredToken);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unrecognizedTokenReturns401ProblemJson() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"un-token-que-nunca-existio\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
