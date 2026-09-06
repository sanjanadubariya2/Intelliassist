package com.intelliassist.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelliassist.backend.exception.AuthenticationFailedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "a-test-signing-key-that-is-at-least-32-bytes-long";

    private final JwtService jwtService =
            new JwtService(new JwtProperties(SECRET, Duration.ofHours(1), "intelliassist"));

    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    /** Decodes the (unencrypted) payload segment, as anyone holding the token could. */
    private String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("[.]")[1]));
    }

    @Test
    void issuedTokenRoundTripsToTheSameIdentity() {
        String token = jwtService.issue(userId, organizationId).token();

        JwtService.TokenIdentity identity = jwtService.parse(token);

        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void expiryIsReturnedSoAClientCanRefreshBeforeItLapses() {
        assertThat(jwtService.issue(userId, organizationId).expiresAt()).isAfter(Instant.now());
    }

    @Test
    void tokenCarriesNoPersonalData() {
        // A JWT is base64, not encrypted. Anything inside is readable by whoever
        // holds it, and tokens end up in browser storage and proxy logs.
        String payload = payloadOf(jwtService.issue(userId, organizationId).token());

        assertThat(payload).doesNotContain("@").doesNotContainIgnoringCase("email");
    }

    @Test
    void roleIsDeliberatelyAbsentFromTheToken() {
        // Authorization state changes. If the role lived in the token, demoting
        // an admin would not take effect until it expired.
        String payload = payloadOf(jwtService.issue(userId, organizationId).token());

        assertThat(payload).doesNotContain("OWNER").doesNotContainIgnoringCase("role");
    }

    @Test
    void eachTokenIsUniqueSoOneCanBeRevokedLater() {
        assertThat(jwtService.issue(userId, organizationId).token())
                .isNotEqualTo(jwtService.issue(userId, organizationId).token());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService attacker =
                new JwtService(
                        new JwtProperties(
                                "a-completely-different-key-also-32-bytes-long",
                                Duration.ofHours(1),
                                "intelliassist"));

        assertThatThrownBy(() -> jwtService.parse(attacker.issue(userId, organizationId).token()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived =
                new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-1), "intelliassist"));

        assertThatThrownBy(() -> jwtService.parse(shortLived.issue(userId, organizationId).token()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtService other =
                new JwtService(new JwtProperties(SECRET, Duration.ofHours(1), "somebody-else"));

        assertThatThrownBy(() -> jwtService.parse(other.issue(userId, organizationId).token()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issue(userId, organizationId).token();

        assertThatThrownBy(() -> jwtService.parse(token.substring(0, token.length() - 4) + "AAAA"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void garbageIsRejectedWithoutLeakingDetail() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid or expired token");
    }

    @Test
    void shortSigningKeyIsRefusedAtStartup() {
        // HMAC-SHA256 needs a key at least as long as its output; a shorter one
        // is silently weak.
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofHours(1), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void secretIsNeverPrinted() {
        assertThat(new JwtProperties(SECRET, Duration.ofHours(1), "x").toString())
                .doesNotContain(SECRET)
                .contains("secret=***");
    }
}
