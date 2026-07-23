package com.auth_service.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica la rama negativa del AC #1 (Story 5.1, Review Findings — patch):
 * cuando PostgreSQL no responde, {@code readiness} debe reflejarlo (503/DOWN)
 * y {@code liveness} debe permanecer en {@code UP}, demostrando que no
 * depende de ningún recurso externo. Clase separada de {@link
 * ActuatorHealthIntegrationTest} porque detiene el contenedor de Postgres
 * deliberadamente y de forma irreversible para el resto de la clase — el
 * {@code @DirtiesContext} evita que el {@code ApplicationContext} (con su
 * {@code DataSource} ya roto) sea reutilizado desde la caché de contexto de
 * Spring Test por cualquier otra clase que comparta la misma configuración.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
		"auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
		"management.server.port=0"
})
class ActuatorHealthDbDownIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

	@LocalManagementPort
	private int managementPort;

	private final RestTemplate restTemplate = new RestTemplate();

	@Test
	void readinessReflectsDbDownWhileLivenessStaysUp() {
		POSTGRES.stop();

		assertThatThrownBy(() -> restTemplate.getForObject(managementUrl("/actuator/health/readiness"), String.class))
				.isInstanceOf(HttpStatusCodeException.class)
				.satisfies(ex -> assertThat(((HttpStatusCodeException) ex).getStatusCode())
						.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

		String livenessBody = restTemplate.getForObject(managementUrl("/actuator/health/liveness"), String.class);
		assertThat(livenessBody).contains("\"status\":\"UP\"");
	}

	private String managementUrl(String path) {
		return "http://localhost:" + managementPort + path;
	}
}
