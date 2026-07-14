package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.AccountId;
import com.auth_service.auth.domain.model.AccountStatus;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.AccountRepository;
import com.auth_service.auth.domain.port.PasswordHasher;
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1 y #2 de la
 * Story 1.7. El AC #3 (sub válido sin Cuenta) solo es alcanzable a nivel
 * unitario/slice — no existe ningún flujo de borrado de Cuenta que lo
 * reproduzca end-to-end (decisión documentada en la Task 4 de la story).
 * El Access Token se obtiene vía login real ({@code POST /auth/login})
 * — mismo patrón que {@code AuthLoginIntegrationTest}/{@code AuthLogoutIntegrationTest}
 * — para ejercitar la cadena completa {@code JwtAuthenticationFilter} →
 * {@code UserController} → {@code GetOwnProfileUseCase} → {@code AccountRepository}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class UserProfileIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AccountId persistActiveAccount(String email) {
        HashedPassword hashedPassword = passwordHasher.hash(new RawPassword("Str0ngPass1"));
        Account account = Account.reconstitute(AccountId.newId(), new Email(email), hashedPassword,
                AccountStatus.ACTIVE, Set.of(Role.USER), 0, null, Instant.now());
        accountRepository.save(account);
        return account.id();
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ngPass1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    @Test
    void validAccessTokenOfActiveAccountReturns200WithItsOwnRealProfileData() throws Exception {
        persistActiveAccount("perfil@example.com");
        String accessToken = loginAndGetAccessToken("perfil@example.com");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("perfil@example.com"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.federatedIdentities").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void missingAuthorizationHeaderReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required."));
    }

    @Test
    void manifestlyInvalidTokenReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer un-token-que-nunca-existio"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required."));
    }
}
