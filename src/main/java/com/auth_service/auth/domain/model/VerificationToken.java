package com.auth_service.auth.domain.model;

import com.auth_service.auth.domain.exception.DomainValidationException;

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
 * Token de un solo uso — AD-5: solo el hash SHA-256 se persiste, el valor
 * crudo ({@code rawToken} en {@link Issued}) es lo único que se envía por
 * email y nunca se guarda. Generación con {@link SecureRandom}/
 * {@link MessageDigest} — JDK puro, sin Spring (AD-1).
 */
public class VerificationToken {

    private final UUID id;
    private final AccountId accountId;
    private final String tokenHash;
    private final VerificationPurpose purpose;
    private final Instant expiresAt;
    private final Instant consumedAt;

    private VerificationToken(UUID id, AccountId accountId, String tokenHash, VerificationPurpose purpose,
                               Instant expiresAt, Instant consumedAt) {
        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
    }

    /** Par (token crudo para email, token con hash para persistir) — nunca guardar {@code rawToken}. */
    public record Issued(String rawToken, VerificationToken token) {
    }

    /** Reconstruye un token ya persistido — usado por el adapter de persistencia. */
    public static VerificationToken reconstitute(UUID id, AccountId accountId, String tokenHash,
                                                  VerificationPurpose purpose, Instant expiresAt, Instant consumedAt) {
        return new VerificationToken(id, accountId, tokenHash, purpose, expiresAt, consumedAt);
    }

    public static Issued issue(AccountId accountId, VerificationPurpose purpose, Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl debe ser una duración positiva.");
        }
        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = Instant.now(clock).plus(ttl);
        VerificationToken token = new VerificationToken(UUID.randomUUID(), accountId, tokenHash, purpose, expiresAt, null);
        return new Issued(rawToken, token);
    }

    /**
     * Devuelve una nueva instancia consumida — este objeto es inmutable, no
     * se muta a sí mismo. Lanza si ya estaba consumido o si expiró; en
     * cualquiera de los dos casos la Cuenta asociada no debe activarse
     * (Story 1.3, AC #2) — el llamador debe fallar antes de tocar `Account`.
     */
    public VerificationToken consume(Clock clock) {
        Instant now = Instant.now(clock);
        if (consumedAt != null) {
            throw new DomainValidationException("El token de verificación ya fue utilizado.");
        }
        if (now.isAfter(expiresAt)) {
            throw new DomainValidationException("El token de verificación ha expirado.");
        }
        return new VerificationToken(id, accountId, tokenHash, purpose, expiresAt, now);
    }

    /** Hash SHA-256 de un token crudo — usado para buscar por {@code tokenHash} al verificar. */
    public static String hashRawToken(String rawToken) {
        return sha256Hex(rawToken);
    }

    private static String generateRawToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
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

    public VerificationPurpose purpose() {
        return purpose;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant consumedAt() {
        return consumedAt;
    }
}
