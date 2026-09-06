package com.intelliassist.backend.security;

import com.intelliassist.backend.exception.AuthenticationFailedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * <p><strong>What goes in the token.</strong> The user id as {@code sub} and the
 * active organization id as {@code org}. That is enough to identify who is
 * calling and on whose behalf, and nothing more.
 *
 * <p><strong>What deliberately does not.</strong> No email, no name, no role. A
 * JWT is base64, not encrypted — anything inside is readable by whoever holds
 * it, and tokens end up in browser storage, proxy logs and bug reports, so
 * personal data does not belong there.
 *
 * <p>The role is excluded for a second reason: it is authorization state that
 * changes. If it lived in the token, demoting an admin would not take effect
 * until their token expired. Identity comes from the token; the role is read
 * from the database on each request, so a membership change applies at once.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_ORGANIZATION = "org";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Issues a token for a user acting within one organization. */
    public IssuedToken issue(UUID userId, UUID organizationId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.expiration());

        String token =
                Jwts.builder()
                        .subject(userId.toString())
                        .claim(CLAIM_ORGANIZATION, organizationId.toString())
                        .issuer(properties.issuer())
                        // A unique id per token, so a future deny-list can
                        // revoke one token without invalidating all of them.
                        .id(UUID.randomUUID().toString())
                        .issuedAt(Date.from(issuedAt))
                        .expiration(Date.from(expiresAt))
                        .signWith(signingKey)
                        .compact();

        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies a token and extracts its identity claims.
     *
     * <p>Signature, expiry and issuer are all checked. Verification failures are
     * logged without the token itself: a valid token in a log file is a
     * credential lying in a log file.
     *
     * @throws AuthenticationFailedException if the token is invalid in any way
     */
    public TokenIdentity parse(String token) {
        try {
            Claims claims =
                    Jwts.parser()
                            .verifyWith(signingKey)
                            .requireIssuer(properties.issuer())
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

            return new TokenIdentity(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_ORGANIZATION, String.class)));

        } catch (JwtException | IllegalArgumentException exception) {
            log.warn("rejected token: {}", exception.getClass().getSimpleName());
            throw new AuthenticationFailedException("Invalid or expired token");
        }
    }

    /**
     * A freshly issued token and when it stops being valid.
     *
     * <p>The expiry is returned so a client can refresh before it lapses rather
     * than discovering expiry through a failed request.
     */
    public record IssuedToken(String token, Instant expiresAt) {

        /** Never print the token. */
        @Override
        public String toString() {
            return "IssuedToken[token=***, expiresAt=%s]".formatted(expiresAt);
        }
    }

    /** The identity claims carried by a valid token. */
    public record TokenIdentity(UUID userId, UUID organizationId) {}
}
