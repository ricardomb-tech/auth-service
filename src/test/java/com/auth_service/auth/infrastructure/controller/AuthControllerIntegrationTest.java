package com.auth_service.auth.infrastructure.controller;

import com.auth_service.auth.infrastructure.adapters.postgresql.AccountJpaRepository;
import com.auth_service.auth.infrastructure.adapters.postgresql.VerificationTokenJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración real contra PostgreSQL vía Testcontainers — AC #1, #2, #3 de
 * la Story 1.2. {@code @Transactional} en la clase envuelve cada test en una
 * transacción que se revierte al final: aísla los tests entre sí sin borrar
 * filas a mano, a costa de que el listener AFTER_COMMIT (Task 4) no dispara
 * durante estos tests — eso no es algo que esta historia pida verificar aquí.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AuthControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountJpaRepository accountJpaRepository;

    @Autowired
    private VerificationTokenJpaRepository verificationTokenJpaRepository;

    @Test
    void newAccountRegistrationCreatesAccountAndVerificationToken() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nueva@example.com\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").exists());

        assertThat(accountJpaRepository.findByEmail("nueva@example.com")).isPresent();
        assertThat(accountJpaRepository.findByEmail("nueva@example.com").orElseThrow().getStatus())
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(verificationTokenJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void duplicateEmailReturnsSameGenericResponseAndDoesNotDuplicateAccount() throws Exception {
        String body = "{\"email\":\"duplicada@example.com\",\"password\":\"Str0ngPass\"}";

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").exists());

        assertThat(accountJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void weakPasswordReturns400ProblemJsonWithDetail() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"visitante@example.com\",\"password\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").exists());

        assertThat(accountJpaRepository.findAll()).isEmpty();
    }

    @Test
    void invalidEmailFormatReturns400ProblemJson() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"Str0ngPass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(accountJpaRepository.findAll()).isEmpty();
    }
}
