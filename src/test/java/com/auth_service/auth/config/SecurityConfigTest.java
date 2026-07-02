package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test (sin base de datos): confirma que la seguridad deny-all
 * (AD-11) y el cuerpo Problem Details (AD-8) funcionan a nivel de filtro,
 * independientemente de qué controllers reales existan. AC #3 de la Story 1.1.
 *
 * <p>{@code @WebMvcTest(controllers = NoOpController.class)} acota
 * deliberadamente el slice a un controller vacío — sin esto, Spring
 * escanearía también {@code AuthController} (Story 1.2) y arrastraría toda
 * su cadena de dependencias (casos de uso, repositorios), que este test no
 * necesita ni quiere levantar.</p>
 */
@WebMvcTest(controllers = SecurityConfigTest.NoOpController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @RestController
    static class NoOpController {
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequestToNonPublicRouteReturns401ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /**
     * Verifica únicamente que el filtro de seguridad no bloquea la ruta
     * pública — NO que springdoc sirva el recurso correctamente (eso lo
     * cubre {@code AuthServiceApplicationTests.swaggerUiIsServedAndPublic}
     * con el contexto completo). Un 404 aquí es válido: en este slice test
     * sin el auto-configure completo de springdoc no hay garantía de que
     * el recurso exista; lo único que este test no debe tolerar es 401/403,
     * que indicarían que `PUBLIC_ENDPOINTS` dejó de cubrir la ruta.
     */
    @Test
    void swaggerUiPathIsNotBlockedBySecurityFilter() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
                });
    }
}
