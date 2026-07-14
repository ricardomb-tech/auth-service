package com.auth_service.auth.infrastructure.adapters.security;

import com.auth_service.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Autentica peticiones con {@code Authorization: Bearer <jwt>} — nunca
 * escribe una respuesta de error él mismo; un token ausente o inválido
 * simplemente deja la petición sin autenticar y continúa la cadena, para
 * que el deny-all + {@code AuthenticationEntryPoint} de {@code SecurityConfig}
 * (Story 1.1) respondan 401 si la ruta lo requiere (AD-3, NFR-5).
 *
 * <p>Acepta la clave actual y, si falla, la anterior (AD-19) — ventana de
 * rotación de {@code JWT_SECRET} sin downtime.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProperties jwtProperties;

    public JwtAuthenticationFilter(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            parseClaims(token).ifPresent(this::authenticate);
        }
        filterChain.doFilter(request, response);
    }

    private Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(parseWithKey(token, jwtProperties.secretCurrent()));
        } catch (ExpiredJwtException expired) {
            // Rutinario (el cliente aún no renovó) — no es una señal de anomalía.
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException currentKeyFailure) {
            String previous = jwtProperties.secretPrevious();
            if (previous == null || previous.isBlank()) {
                log.warn("Token JWT rechazado con la clave actual: {}", currentKeyFailure.toString());
                return Optional.empty();
            }
            try {
                return Optional.of(parseWithKey(token, previous));
            } catch (ExpiredJwtException expired) {
                return Optional.empty();
            } catch (JwtException | IllegalArgumentException previousKeyFailure) {
                log.warn("Token JWT rechazado con la clave actual y con la anterior: {}", previousKeyFailure.toString());
                return Optional.empty();
            }
        }
    }

    private Claims parseWithKey(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(jwtProperties.issuer())
                .build()
                .parseSignedClaims(token);
        return parsed.getPayload();
    }

    private void authenticate(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            log.debug("Token JWT válido pero sin claim 'sub' — no se autentica la petición.");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, null, extractAuthorities(claims)));
    }

    @SuppressWarnings("unchecked")
    private List<GrantedAuthority> extractAuthorities(Claims claims) {
        List<String> roles;
        try {
            roles = claims.get("roles", List.class);
        } catch (RuntimeException malformedRolesClaim) {
            log.debug("Token JWT con claim 'roles' de tipo inesperado — se ignora: {}", malformedRolesClaim.toString());
            return List.of();
        }
        return roles == null
                ? List.of()
                : roles.stream().map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}
