package com.auth_service.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AdminBootstrapPropertiesTest {

    @Test
    void constructionWithNullEmailAndPasswordDoesNotThrow() {
        assertThatCode(() -> new AdminBootstrapProperties(null, null)).doesNotThrowAnyException();
    }

    @Test
    void exposesBothValuesAsProvided() {
        AdminBootstrapProperties properties = new AdminBootstrapProperties("admin@example.com", "Str0ngAdminPass1");

        assertThat(properties.email()).isEqualTo("admin@example.com");
        assertThat(properties.password()).isEqualTo("Str0ngAdminPass1");
    }
}
