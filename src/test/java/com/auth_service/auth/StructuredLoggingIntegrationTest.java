package com.auth_service.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import com.auth_service.auth.infrastructure.adapters.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifica que cada petición HTTP propaga un {@code traceId} (Micrometer
 * Tracing + puente OpenTelemetry) presente en el MDC de los logs asociados,
 * y que la salida de consola es JSON parseable (Story 5.1, AC #2, AD-16).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
		"auth.jwt.secret-current=test-only-jwt-secret-not-for-production-use-32bytes",
		"management.server.port=0"
})
class StructuredLoggingIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

	@LocalServerPort
	private int serverPort;

	private final RestTemplate restTemplate = new RestTemplate();

	private ListAppender<ILoggingEvent> listAppender;
	private Logger rootLogger;

	@BeforeEach
	void attachListAppender() {
		rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		listAppender = new ListAppender<>();
		listAppender.start();
		rootLogger.addAppender(listAppender);
	}

	@AfterEach
	void detachListAppender() {
		rootLogger.detachAppender(listAppender);
	}

	@Test
	void httpRequestLogLineIncludesNonBlankTraceId() {
		callProtectedEndpointWithInvalidToken();

		// Se filtra por el logger de JwtAuthenticationFilter (en vez de aceptar
		// "cualquier" evento capturado por el root logger) porque ese logger solo
		// emite en respuesta directa a esta petición — evita que un traceId
		// residual de un hilo/petición no relacionada (p. ej. un intento fallido
		// de exportación OTLP en background) pase la aserción por casualidad. El
		// AC #2 pide el traceId en "cada línea de log asociada a esa petición":
		// se exige sobre TODAS las líneas de ese logger, no solo sobre alguna.
		List<String> jwtFilterTraceIds = listAppender.list.stream()
				.filter(event -> event.getLoggerName().equals(JwtAuthenticationFilter.class.getName()))
				.map(event -> event.getMDCPropertyMap().get("traceId"))
				.toList();

		assertThat(jwtFilterTraceIds)
				.as("JwtAuthenticationFilter debe haber logueado el rechazo del token durante la petición")
				.isNotEmpty();
		assertThat(jwtFilterTraceIds)
				.as("toda línea de log de JwtAuthenticationFilter asociada a la petición debe tener un traceId no vacío en el MDC")
				.allMatch(traceId -> traceId != null && !traceId.isBlank());
	}

	@Test
	@SuppressWarnings("unchecked")
	void consoleOutputIsStructuredJson() {
		// No se captura System.out directamente: el ConsoleAppender de Logback
		// abre su OutputStream una sola vez al arrancar y cachea esa referencia,
		// así que reasignar System.out después no lo afecta. En su lugar, se
		// reutiliza el mismo Encoder ya configurado (logging.structured.format.console=logstash)
		// para codificar un evento real capturado por el ListAppender — valida
		// el formato de salida real sin depender de un truco de redirección frágil.
		callProtectedEndpointWithInvalidToken();

		ILoggingEvent event = listAppender.list.stream()
				.findFirst()
				.orElseThrow(() -> new AssertionError("no se capturó ningún evento de log durante la petición"));

		Appender<ILoggingEvent> consoleAppender = rootLogger.getAppender("CONSOLE");
		assertThat(consoleAppender).as("el appender CONSOLE por defecto de Spring Boot debe existir").isNotNull();

		Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) consoleAppender).getEncoder();
		String line = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();

		assertThatCode(() -> new ObjectMapper().readTree(line))
				.as("la línea codificada por el appender de consola debe ser JSON parseable: " + line)
				.doesNotThrowAnyException();
	}

	/**
	 * Dispara {@code JwtAuthenticationFilter.log.warn("Token JWT rechazado...")}
	 * de forma determinista en cada llamada — a diferencia de un endpoint
	 * público como {@code /v3/api-docs}, que solo loguea en la primera
	 * petición del ciclo de vida de la JVM (inicialización lazy de
	 * springdoc) y deja los tests flaky según el orden de ejecución.
	 */
	private void callProtectedEndpointWithInvalidToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", "Bearer invalid-token");
		try {
			restTemplate.exchange(businessUrl("/api/v1/users/me"), org.springframework.http.HttpMethod.GET,
					new HttpEntity<>(headers), String.class);
		} catch (org.springframework.web.client.HttpClientErrorException expected401) {
			// Un token inválido responde 401 — lo que importa para este test es
			// el log emitido por JwtAuthenticationFilter, no la respuesta HTTP.
		}
	}

	private String businessUrl(String path) {
		return "http://localhost:" + serverPort + path;
	}
}
