package com.auth_service.auth.infrastructure.adapters.security;

import com.auth_service.auth.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private static final String CURRENT_SECRET = "current-jwt-signing-secret-must-be-at-least-32-bytes";
    private static final String PREVIOUS_SECRET = "previous-jwt-signing-secret-must-be-at-least-32-bytes";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSignedWithCurrentKeyAuthenticates() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, PREVIOUS_SECRET, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        String token = tokenSignedWith(CURRENT_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("account-123");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void validTokenSignedWithPreviousKeyAuthenticatesDuringRotationWindow() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, PREVIOUS_SECRET, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        String token = tokenSignedWith(PREVIOUS_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingAuthorizationHeaderContinuesChainWithoutAuthenticating() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredOrMalformedTokenContinuesChainWithoutAuthenticatingOrThrowing() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredTokenContinuesChainWithoutAuthenticatingOrThrowing() throws Exception {
        // ExpiredJwtException se maneja aparte del resto de fallos JWT (Review
        // Findings, Story 1.5): es tráfico rutinario, no una anomalía a
        // loguear a WARN como firma inválida o token malformado.
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        SecretKey key = Keys.hmacShaKeyFor(CURRENT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String token = Jwts.builder()
                .subject("account-123")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(15))))
                .expiration(Date.from(past))
                .issuer("auth-service")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void tokenWithMismatchedIssuerDoesNotAuthenticate() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        String token = tokenSignedWith(CURRENT_SECRET, claims -> claims.issuer("otro-servicio"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void tokenWithoutSubjectDoesNotAuthenticate() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        String token = tokenSignedWith(CURRENT_SECRET, claims -> claims.subject(null));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void tokenWithNonListRolesClaimAuthenticatesWithNoAuthoritiesInsteadOfThrowing() throws Exception {
        JwtProperties properties = new JwtProperties(CURRENT_SECRET, null, Duration.ofMinutes(15), "auth-service");
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties);
        String token = tokenSignedWith(CURRENT_SECRET, claims -> claims.claim("roles", "USER"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities()).isEmpty();
        verify(chain).doFilter(request, response);
    }

    private String tokenSignedWith(String secret) {
        return tokenSignedWith(secret, claims -> {
        });
    }

    private String tokenSignedWith(String secret, java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> customizer) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject("account-123")
                .claim("email", "titular@example.com")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(15))))
                .issuer("auth-service");
        customizer.accept(builder);
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
