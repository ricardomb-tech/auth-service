package com.auth_service.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que el contexto de Spring arranca completo (seguridad, JPA,
 * Flyway) contra una PostgreSQL real vía Testcontainers — AD-7,
 * "Consistency Conventions / Tests" del architecture spine. AC #1 y #3 de
 * la Story 1.1.
 *
 * <p>{@code @ServiceConnection} hace que Spring Boot construya el
 * {@code DataSource} (y la conexión que usa Flyway) directamente desde el
 * contenedor, sin pasar por los placeholders {@code ${DB_HOST}} etc. de
 * {@code application.properties} — por eso no hace falta activar ningún
 * perfil ni definir esas variables para este test.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes")
class AuthServiceApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

	@org.springframework.beans.factory.annotation.Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void openApiDocsAreServedAndPublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk());
	}

	@Test
	void swaggerUiIsServedAndPublic() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection());
	}

}
