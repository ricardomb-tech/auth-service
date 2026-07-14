package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
import com.auth_service.auth.infrastructure.adapters.postgresql.RefreshTokenJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1..#4 de la
 * Story 1.4. Cada Cuenta se crea directamente vía {@link AccountRepository}/
 * {@link PasswordHasher} reales (no vía {@code POST /auth/register}), mismo
 * límite documentado en las Stories 1.2/1.3 sobre el listener AFTER_COMMIT
 * bajo {@code @Transactional} de test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthLoginIntegrationTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32bytes";

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
    private RefreshTokenJpaRepository refreshTokenJpaRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Account persistAccount(String email, AccountStatus status) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account account = Account.reconstitute(
                com.auth_service.auth.domain.model.AccountId.newId(), new Email(email), hashedPassword,
                status, Set.of(Role.USER), 0, null, Instant.now());
        return accountRepository.save(account);
    }

    @Test
    void activeAccountWithCorrectCredentialsReturns200WithValidTokensAndPersistsRefreshToken() throws Exception {
        persistAccount("titular@example.com", AccountStatus.ACTIVE);

        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"titular@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        String accessToken = json.get("accessToken").asText();
        String refreshToken = json.get("refreshToken").asText();

        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Jws<Claims> parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken);
        assertThat(parsed.getPayload().get("email", String.class)).isEqualTo("titular@example.com");
        assertThat(parsed.getPayload().get("roles", List.class)).containsExactly("USER");

        String expectedHash = RefreshToken.hashRawToken(refreshToken);
        assertThat(refreshTokenJpaRepository.findAll())
                .anyMatch(entity -> entity.getTokenHash().equals(expectedHash));
    }

    @Test
    void nonExistentEmailReturns401ProblemJsonWithGenericMessage() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void wrongPasswordOnActiveAccountReturns401SameGenericMessage() throws Exception {
        persistAccount("titular2@example.com", AccountStatus.ACTIVE);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"titular2@example.com\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void pendingVerificationAccountWithCorrectPasswordReturns401() throws Exception {
        persistAccount("pendiente@example.com", AccountStatus.PENDING_VERIFICATION);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pendiente@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void lockedAccountWithCorrectPasswordReturns401() throws Exception {
        persistAccount("bloqueada@example.com", AccountStatus.LOCKED);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bloqueada@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void disabledAccountWithCorrectPasswordReturns401() throws Exception {
        persistAccount("deshabilitada@example.com", AccountStatus.DISABLED);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"deshabilitada@example.com\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
