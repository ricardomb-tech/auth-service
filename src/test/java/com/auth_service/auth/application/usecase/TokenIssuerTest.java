package com.auth_service.auth.application.usecase;

import com.auth_service.auth.config.AuthTokenProperties;
import com.auth_service.auth.config.JwtProperties;
import com.auth_service.auth.domain.model.Account;
import com.auth_service.auth.domain.model.Email;
import com.auth_service.auth.domain.model.HashedPassword;
import com.auth_service.auth.domain.model.RefreshToken;
import com.auth_service.auth.domain.port.RefreshTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenIssuerTest {

    private static final String SECRET = "unit-test-jwt-signing-secret-must-be-32-bytes-min";

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
    private final JwtProperties jwtProperties = new JwtProperties(SECRET, null, Duration.ofMinutes(15), "auth-service");
    private final AuthTokenProperties authTokenProperties = new AuthTokenProperties(null, Duration.ofDays(7));
    private TokenIssuer tokenIssuer;

    @BeforeEach
    void setUp() {
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        tokenIssuer = new TokenIssuer(refreshTokenRepository, jwtProperties, authTokenProperties, clock);
    }

    @Test
    void issueBuildsAJwtWithTheExpectedClaims() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("bcrypt-hash"));

        TokenIssuer.IssuedTokens tokens = tokenIssuer.issue(account);

        Jws<Claims> parsed = parse(tokens.accessToken());
        Claims claims = parsed.getPayload();
        assertThat(claims.getSubject()).isEqualTo(account.id().value().toString());
        assertThat(claims.get("email", String.class)).isEqualTo("titular@example.com");
        assertThat(claims.get("roles", List.class)).containsExactly("USER");
        assertThat(claims.getIssuer()).isEqualTo("auth-service");
        assertThat(claims.getExpiration().toInstant().minusMillis(claims.getIssuedAt().toInstant().toEpochMilli()).toEpochMilli())
                .isEqualTo(Duration.ofMinutes(15).toMillis());
        assertThat(tokens.accessTokenExpiresInSeconds()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void issueReturnsTheRawRefreshTokenNeverTheHash() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("bcrypt-hash"));

        TokenIssuer.IssuedTokens tokens = tokenIssuer.issue(account);

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(savedCaptor.capture());
        assertThat(tokens.refreshToken()).isNotEqualTo(savedCaptor.getValue().tokenHash());
        assertThat(savedCaptor.getValue().familyId()).isNotNull();
    }

    @Test
    void twoIssuancesForTheSameAccountUseDifferentFamilies() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("bcrypt-hash"));

        tokenIssuer.issue(account);
        tokenIssuer.issue(account);

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(savedCaptor.capture());
        List<RefreshToken> saved = savedCaptor.getAllValues();
        assertThat(saved.get(0).familyId()).isNotEqualTo(saved.get(1).familyId());
    }

    @Test
    void issueWithExplicitFamilyIdReusesThatFamilyInsteadOfGeneratingANewOne() {
        Account account = Account.register(new Email("titular@example.com"), new HashedPassword("bcrypt-hash"));
        java.util.UUID existingFamilyId = java.util.UUID.randomUUID();

        TokenIssuer.IssuedTokens tokens = tokenIssuer.issue(account, existingFamilyId);

        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().familyId()).isEqualTo(existingFamilyId);

        Jws<Claims> parsed = parse(tokens.accessToken());
        assertThat(parsed.getPayload().getSubject()).isEqualTo(account.id().value().toString());
        assertThat(parsed.getPayload().get("roles", List.class)).containsExactly("USER");
    }

    @Test
    void rejectsConstructionWhenAccessTtlIsNotShorterThanRefreshTtl() {
        JwtProperties equalTtl = new JwtProperties(SECRET, null, Duration.ofHours(1), "auth-service");
        AuthTokenProperties refreshTtl = new AuthTokenProperties(null, Duration.ofHours(1));

        assertThatThrownBy(() -> new TokenIssuer(refreshTokenRepository, equalTtl, refreshTtl, clock))
                .isInstanceOf(IllegalStateException.class);
    }

    private Jws<Claims> parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        // El parser valida `exp` contra su propio reloj (real por defecto) — se
        // sobreescribe con el mismo Clock fijo usado para emitir el token, si no
        // un token construido con una fecha fija en el pasado real se ve expirado.
        return Jwts.parser()
                .clock(() -> Date.from(Instant.now(clock)))
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }
}
