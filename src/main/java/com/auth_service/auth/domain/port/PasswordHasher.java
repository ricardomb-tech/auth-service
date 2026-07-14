package com.auth_service.auth.domain.port;

import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RawPassword;

public interface PasswordHasher {

    HashedPassword hash(RawPassword rawPassword);

    /**
     * Verifica una contraseña cruda contra un hash. Recibe {@code String},
     * no {@link RawPassword} — a diferencia de {@link #hash}, este es el
     * camino de verificación (login), no de creación, y no debe aplicar la
     * política de formato de {@code RawPassword} (una contraseña que no la
     * cumple hoy pudo ser válida bajo una política anterior; además,
     * lanzar una excepción de formato aquí filtraría información en un
     * flujo que debe responder siempre el mismo error genérico).
     */
    boolean matches(String rawPassword, HashedPassword hashedPassword);
}
