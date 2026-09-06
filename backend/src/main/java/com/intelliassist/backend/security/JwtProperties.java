package com.intelliassist.backend.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing configuration.
 *
 * <p>The secret is validated at startup so a deployment cannot come up issuing
 * tokens signed with a blank or default key.
 *
 * @param secret     HMAC-SHA256 key; must be at least 32 bytes
 * @param expiration how long an issued token stays valid
 * @param issuer     the {@code iss} claim, checked on every parse
 */
@Validated
@ConfigurationProperties(prefix = "intelliassist.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT_SECRET must be set") String secret,
        Duration expiration,
        String issuer) {

    /**
     * HMAC-SHA256 requires a key at least as long as its output. A shorter key
     * is silently weak, so jjwt rejects it — better to fail here with a message
     * that names the variable.
     */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        expiration = expiration != null ? expiration : Duration.ofHours(12);
        issuer = issuer != null && !issuer.isBlank() ? issuer : "intelliassist";

        if (secret != null && secret.getBytes().length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least %d bytes (was %d). Generate one with: openssl rand -base64 48"
                            .formatted(MIN_SECRET_BYTES, secret.getBytes().length));
        }
    }

    /** Redacts the signing key: leaking it means anyone can mint valid tokens. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***, expiration=%s, issuer=%s]".formatted(expiration, issuer);
    }
}
