package com.auth_service.auth.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh Token opaco — AD-4: solo el hash SHA-256 se persiste, el valor
 * crudo ({@code rawToken} en {@link Issued}) es lo único que se devuelve al
 * cliente y nunca se guarda. Java puro — sin Spring (AD-1).
 *
 * <p>Cada token pertenece a una {@code familyId}: un login crea una familia
 * nueva ({@link com.auth_service.auth.application.usecase.TokenIssuer}); la
 * futura rotación (Story 1.5) reutiliza {@link #issue} con la familia
 * existente, por eso el parámetro ya es explícito desde esta historia.</p>
 */
public class RefreshToken {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UUID id;
    private final AccountId accountId;
    private final String tokenHash;
    private final UUID familyId;
    private final Instant expiresAt;
    private final Instant usedAt;
    private final Instant revokedAt;

    private RefreshToken(UUID id, AccountId accountId, String tokenHash, UUID familyId,
                          Instant expiresAt, Instant usedAt, Instant revokedAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
    }

    /** Par (token crudo para el cliente, token con hash para persistir) — nunca guardar {@code rawToken}. */
    public record Issued(String rawToken, RefreshToken token) {
    }

    /**
     * Reconstruye un token ya persistido — usado únicamente por el adapter de
     * persistencia (mapeo entidad→dominio). La mutación real de {@code usedAt}/
     * {@code revokedAt} en esta historia ocurre solo vía UPDATE condicional a
     * nivel de repositorio, nunca reconstruyendo con nuevo estado y guardando.
     */
    public static RefreshToken reconstitute(UUID id, AccountId accountId, String tokenHash, UUID familyId,
                                             Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return new RefreshToken(id, accountId, tokenHash, familyId, expiresAt, usedAt, revokedAt);
    }

    public static Issued issue(AccountId accountId, UUID familyId, Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl debe ser una duración positiva.");
        }
        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = Instant.now(clock).plus(ttl);
        RefreshToken token = new RefreshToken(UUID.randomUUID(), accountId, tokenHash, familyId, expiresAt, null, null);
        return new Issued(rawToken, token);
    }

    /** Hash SHA-256 de un token crudo — expuesto para que la Story 1.5 (lookup por hash) y los tests puedan verificarlo sin duplicar el algoritmo. */
    public static String hashRawToken(String rawToken) {
        return sha256Hex(rawToken);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es un algoritmo estándar garantizado por el JDK (JLS); inalcanzable en la práctica.
            throw new IllegalStateException("SHA-256 no disponible en este JDK.", e);
        }
    }

    public UUID id() {
        return id;
    }

    public AccountId accountId() {
        return accountId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public UUID familyId() {
        return familyId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
