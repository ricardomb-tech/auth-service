package com.auth_service.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica el puerto de management separado y los grupos de salud de
 * Actuator (Story 5.1, AC #1, AD-11, AD-16). {@code management.server.port=0}
 * en test evita colisión de puerto fijo en CI, igual que el puerto de
 * negocio con {@code RANDOM_PORT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
		"auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
		"management.server.port=0"
})
class ActuatorHealthIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

	@LocalServerPort
	private int serverPort;

	@LocalManagementPort
	private int managementPort;

	private final RestTemplate restTemplate = new RestTemplate();

	@Test
	void readinessProbeReturns200WithDbComponentUp() {
		String body = restTemplate.getForObject(managementUrl("/actuator/health/readiness"), String.class);

		assertThat(body).contains("\"status\":\"UP\"");
	}

	@Test
	void livenessProbeReturns200() {
		String body = restTemplate.getForObject(managementUrl("/actuator/health/liveness"), String.class);

		assertThat(body).contains("\"status\":\"UP\"");
	}

	@Test
	void prometheusEndpointIsServedOnManagementPort() {
		// Genera al menos una petición de negocio real para que el filtro de
		// métricas de Spring MVC (WebMvcMetricsFilter) registre una muestra de
		// http_server_requests_seconds antes de scrapear.
		restTemplate.getForEntity(businessUrl("/v3/api-docs"), String.class);

		String body = restTemplate.getForObject(managementUrl("/actuator/prometheus"), String.class);

		assertThat(body).contains("http_server_requests_seconds");
	}

	@Test
	void actuatorIsNotReachableOnTheBusinessPort() {
		assertThatThrownBy(() -> restTemplate.getForObject(businessUrl("/actuator/health"), String.class))
				.isInstanceOf(HttpClientErrorException.class)
				.satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	private String managementUrl(String path) {
		return "http://localhost:" + managementPort + path;
	}

	private String businessUrl(String path) {
		return "http://localhost:" + serverPort + path;
	}
}
