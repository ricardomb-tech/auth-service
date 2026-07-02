package com.auth_service.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Auth Service API")
                        .version("v1")
                        .description("Microservicio de identidad — registro, autenticación, sesiones y administración de cuentas."));
    }
}
