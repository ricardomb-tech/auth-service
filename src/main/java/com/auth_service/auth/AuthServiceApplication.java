package com.auth_service.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

	/** Inyectado en vez de {@code Clock.systemUTC()} disperso — testeable/determinista donde se use. */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
