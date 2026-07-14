package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.model.Role;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Punto único de emisión de tokens (AD-2) — todo par Access+Refresh se
 * construye aquí, para login (esta historia) y, más adelante, login
 * federado (Epic 2) y renovación (Story 1.5). Ningún otro caso de uso
 * construye JWTs.
 */
@Service
public class TokenIssuer {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;

    public TokenIssuer(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties,
                        AuthTokenProperties authTokenProperties, Clock clock) {
        if (jwtProperties.accessTtl().compareTo(authTokenProperties.refreshTtl()) >= 0) {
            throw new IllegalStateException(
                    "auth.jwt.access-ttl debe ser menor que auth.token.refresh-ttl.");
        }
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.authTokenProperties = authTokenProperties;
        this.clock = clock;
    }

    /** {@code accessTokenExpiresInSeconds} deriva del mismo TTL usado para el claim {@code exp} del token — nunca se recalcula por separado. */
    public record IssuedTokens(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {
    }

    /** Emite un par Access+Refresh para una sesión nueva (login) — crea una Familia de Refresh Token nueva. */
    public IssuedTokens issue(Account account) {
        return issue(account, UUID.randomUUID());
    }

    /** Emite un par Access+Refresh reutilizando una Familia existente (Story 1.5 — rotación en {@code /auth/refresh}). */
    public IssuedTokens issue(Account account, UUID familyId) {
        Duration accessTtl = jwtProperties.accessTtl();
        String accessToken = buildAccessToken(account, accessTtl);

        RefreshToken.Issued issued = RefreshToken.issue(
                account.id(), familyId, authTokenProperties.refreshTtl(), clock);
        refreshTokenRepository.save(issued.token());

        return new IssuedTokens(accessToken, issued.rawToken(), accessTtl.toSeconds());
    }

    private String buildAccessToken(Account account, Duration accessTtl) {
        SecretKey signingKey = Keys.hmacShaKeyFor(jwtProperties.secretCurrent().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(accessTtl);
        List<String> roles = account.roles().stream().map(Role::name).toList();

        return Jwts.builder()
                .subject(account.id().value().toString())
                .claim("email", account.email().value())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .issuer(jwtProperties.issuer())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
