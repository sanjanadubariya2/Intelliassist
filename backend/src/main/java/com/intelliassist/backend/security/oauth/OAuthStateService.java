package com.intelliassist.backend.security.oauth;

import com.intelliassist.backend.entity.IdentityProvider;
import com.intelliassist.backend.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * The {@code state} parameter of the OAuth round-trip.
 *
 * <p>The backend is stateless, so there is no session to remember which
 * sign-in the browser started. Instead the state is a short-lived signed token
 * naming the provider and a random nonce, and the same nonce goes into an
 * HttpOnly cookie. On the way back both must agree. That stops two things:
 *
 * <ul>
 *   <li>a forged or replayed callback, because the signature and expiry are
 *       checked; and
 *   <li>login CSRF, where an attacker gets a victim's browser to complete a
 *       sign-in the attacker started, because the victim's browser does not
 *       hold the attacker's nonce cookie.
 * </ul>
 *
 * <p>It is signed with the JWT key but under its own issuer, so a state token
 * can never be accepted as a session token or the other way round.
 */
@Service
public class OAuthStateService {

    static final String ISSUER = "intelliassist-oauth-state";

    /** How long a started sign-in stays valid. The nonce cookie lives as long. */
    public static final Duration TTL = Duration.ofMinutes(10);
    private static final String CLAIM_NONCE = "nonce";

    private final SecretKey key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateService(JwtProperties jwtProperties) {
        this(jwtProperties, Clock.systemUTC());
    }

    OAuthStateService(JwtProperties jwtProperties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** A new state token and the nonce that must come back in the cookie. */
    public IssuedState issue(IdentityProvider provider) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant now = clock.instant();
        String state =
                Jwts.builder()
                        .issuer(ISSUER)
                        .subject(provider.id())
                        .claim(CLAIM_NONCE, nonce)
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plus(TTL)))
                        .signWith(key)
                        .compact();
        return new IssuedState(state, nonce);
    }

    /**
     * True only if the state is genuine, unexpired, for this provider, and
     * carries the nonce from the browser's cookie.
     */
    public boolean verify(String state, IdentityProvider provider, String cookieNonce) {
        if (state == null || cookieNonce == null || cookieNonce.isBlank()) {
            return false;
        }
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(key)
                            .requireIssuer(ISSUER)
                            .clock(() -> Date.from(clock.instant()))
                            .build()
                            .parseSignedClaims(state)
                            .getPayload();
            return provider.id().equals(claims.getSubject())
                    && constantTimeEquals(cookieNonce, claims.get(CLAIM_NONCE, String.class));
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public record IssuedState(String state, String nonce) {}
}
