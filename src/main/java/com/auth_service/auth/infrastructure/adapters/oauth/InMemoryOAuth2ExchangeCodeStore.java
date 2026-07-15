package com.auth_service.auth.infrastructure.adapters.oauth;

import com.auth_service.auth.domain.port.OAuth2ExchangeCodeStore;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación en memoria de {@link OAuth2ExchangeCodeStore} — mismo
 * espíritu que {@link CookieOAuth2AuthorizationRequestRepository}: mecanismo
 * puramente de infraestructura, sin significado de negocio, por eso no pasa
 * por {@code application/usecase} para su propia escritura.
 *
 * <p>No requiere persistencia en base de datos: el código vive segundos
 * (canje inmediato tras el redirect), y sobrevivir a un reinicio del proceso
 * no aporta nada (el navegador de todas formas ya perdió la sesión OAuth2 en
 * curso). Una sola instancia de proceso es suficiente para el alcance actual
 * de este servicio (sin balanceo multi-instancia con afinidad de sesión
 * documentado) — si eso cambia, este store pasa a un backend compartido
 * (Redis u otro) sin tocar el puerto {@link OAuth2ExchangeCodeStore}.</p>
 */
@Component
public class InMemoryOAuth2ExchangeCodeStore implements OAuth2ExchangeCodeStore {

    private static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Entry> codes = new ConcurrentHashMap<>();

    public InMemoryOAuth2ExchangeCodeStore(Clock clock) {
        this.clock = clock;
    }

    private record Entry(IssuedTokens tokens, Instant expiresAt) {
    }

    @Override
    public String issue(IssuedTokens tokens) {
        purgeExpired();
        String code = generateCode();
        codes.put(code, new Entry(tokens, Instant.now(clock).plus(CODE_TTL)));
        return code;
    }

    @Override
    public Optional<IssuedTokens> redeem(String code) {
        if (code == null) {
            return Optional.empty();
        }
        // remove: un solo uso — la segunda petición con el mismo código,
        // válido o no, no encuentra nada que canjear.
        Entry entry = codes.remove(code);
        if (entry == null || Instant.now(clock).isAfter(entry.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(entry.tokens());
    }

    private void purgeExpired() {
        Instant now = Instant.now(clock);
        codes.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
    }

    private static String generateCode() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
