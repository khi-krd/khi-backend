package ak.dev.khi_backend.user.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import ak.dev.khi_backend.user.model.Session;
import ak.dev.khi_backend.user.model.User;
import ak.dev.khi_backend.user.repo.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static ak.dev.khi_backend.user.consts.SecurityConstants.*;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-ms:604800000}")
    private long expirationTime;

    private final SessionRepository sessionRepository;

    public long getExpirationTime() {
        return expirationTime;
    }

    public String generateToken(User user, HttpServletRequest request) {
        try {
            String[] claims = extractUserAuthorities(user);
            Instant now = Instant.now();
            Instant expiration = now.plusMillis(expirationTime);

            Session session = Session.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .user(user)
                    .deviceInfo(request.getHeader("User-Agent"))
                    .ipAddress(request.getRemoteAddr())
                    .loginTimestamp(now)
                    .expiresAt(expiration)
                    .isActive(true)
                    .build();

            sessionRepository.save(session);

            return JWT.create()
                    .withIssuer(AKAR_ARKAN)
                    .withAudience(AKAR_ARKAN_ADMINISTRATION)
                    .withIssuedAt(Date.from(now))
                    .withSubject(user.getUsername())
                    .withClaim(ID_CLAIM, user.getUserId())
                    .withClaim(ROLE, user.getRole().name())
                    .withArrayClaim(AUTHORITIES, claims)
                    .withClaim("sessionId", session.getSessionId())
                    .withExpiresAt(Date.from(expiration))
                    .sign(Algorithm.HMAC256(secret));

        } catch (Exception e) {
            logger.error("Error generating JWT token", e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    private String[] extractUserAuthorities(User user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toArray(String[]::new);
    }

    /**
     * Sliding-session renewal: re-mints the incoming (still-valid) token with
     * the same claims and sessionId but a fresh expiry, and slides the session
     * row's expiresAt forward to match. Returns null when the token cannot be
     * renewed (missing session, malformed claims) — callers treat null as
     * "keep the current token".
     */
    public String renewToken(String token) {
        try {
            DecodedJWT decoded = createJWTVerifier().verify(token);
            String sessionId = decoded.getClaim("sessionId").asString();
            if (sessionId == null || sessionId.isBlank()) {
                return null;
            }
            Instant now = Instant.now();
            Instant expiration = now.plusMillis(expirationTime);
            sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
                session.setExpiresAt(expiration);
                sessionRepository.save(session);
            });
            String role = decoded.getClaim(ROLE).asString();
            String[] authorities = decoded.getClaim(AUTHORITIES).asArray(String.class);
            return JWT.create()
                    .withIssuer(AKAR_ARKAN)
                    .withAudience(AKAR_ARKAN_ADMINISTRATION)
                    .withIssuedAt(Date.from(now))
                    .withSubject(decoded.getSubject())
                    .withClaim(ID_CLAIM, decoded.getClaim(ID_CLAIM).asLong())
                    .withClaim(ROLE, role)
                    .withArrayClaim(AUTHORITIES, authorities != null ? authorities : new String[0])
                    .withClaim("sessionId", sessionId)
                    .withExpiresAt(Date.from(expiration))
                    .sign(Algorithm.HMAC256(secret));
        } catch (Exception e) {
            logger.warn("Failed to renew token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Milliseconds until the token's own exp claim, or a negative value when
     * already expired / unreadable.
     */
    public long getRemainingMillis(String token) {
        try {
            Instant exp = decodeToken(token).getExpiresAtAsInstant();
            return exp == null ? -1 : exp.toEpochMilli() - Instant.now().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * ── FIX ──────────────────────────────────────────────────────────────────
     * Principal is now a UserDetails instance instead of a plain String.
     * This makes @AuthenticationPrincipal UserDetails resolve correctly in
     * every controller (e.g. UserProfileAPI#getMe).
     * ─────────────────────────────────────────────────────────────────────────
     */
    public Authentication getAuthentication(UserDetails userDetails,
                                            List<GrantedAuthority> authorities,
                                            HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authToken;
    }

    /**
     * Validates token (checks signature and expiration).
     * Throws TokenExpiredException if token is expired.
     */
    public String getSubject(String token) throws TokenExpiredException {
        JWTVerifier verifier = createJWTVerifier();
        return verifier.verify(token).getSubject();
    }

    private JWTVerifier createJWTVerifier() {
        return JWT.require(Algorithm.HMAC256(secret))
                .withIssuer(AKAR_ARKAN)
                .withAudience(AKAR_ARKAN_ADMINISTRATION)
                .build();
    }

    public List<GrantedAuthority> getAuthorities(String token) {
        String[] claims = extractAuthoritiesFromToken(token);
        return Arrays.stream(claims)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public String[] extractAuthoritiesFromToken(String token) {
        try {
            JWTVerifier verifier = createJWTVerifier();
            return verifier.verify(token).getClaim(AUTHORITIES).asArray(String.class);
        } catch (Exception e) {
            logger.error("Failed to extract authorities from token", e);
            return new String[0];
        }
    }

    public Long getUserIdFromToken(String token) {
        try {
            DecodedJWT decodedJWT = decodeToken(token);
            return decodedJWT.getClaim(ID_CLAIM).asLong();
        } catch (Exception e) {
            logger.error("Failed to extract user ID from token", e);
            throw new IllegalArgumentException("Invalid token", e);
        }
    }

    public DecodedJWT decodeToken(String token) {
        JWTVerifier verifier = createJWTVerifier();
        return verifier.verify(token);
    }

    public String getSessionIdFromToken(String token) {
        try {
            DecodedJWT decodedJWT = decodeToken(token);
            return decodedJWT.getClaim("sessionId").asString();
        } catch (Exception e) {
            logger.error("Failed to extract session ID from token", e);
            return null;
        }
    }
}