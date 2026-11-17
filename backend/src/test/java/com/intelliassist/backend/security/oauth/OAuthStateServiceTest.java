package com.intelliassist.backend.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelliassist.backend.entity.IdentityProvider;
import com.intelliassist.backend.security.JwtProperties;
import com.intelliassist.backend.security.JwtService;
import com.intelliassist.backend.exception.AuthenticationFailedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OAuthStateServiceTest {

    private static final JwtProperties PROPS =
            new JwtProperties(
                    "test-only-signing-key-not-a-real-secret-at-least-32-bytes",
                    Duration.ofHours(1),
                    "intelliassist");

    private final OAuthStateService service = new OAuthStateService(PROPS);

    @Test
    void acceptsItsOwnStateWithTheMatchingNonce() {
        var issued = service.issue(IdentityProvider.GOOGLE);
        assertThat(service.verify(issued.state(), IdentityProvider.GOOGLE, issued.nonce())).isTrue();
    }

    @Test
    void rejectsADifferentNonce() {
        // Login CSRF: the victim's browser does not hold the attacker's cookie.
        var issued = service.issue(IdentityProvider.GOOGLE);
        var other = service.issue(IdentityProvider.GOOGLE);
        assertThat(service.verify(issued.state(), IdentityProvider.GOOGLE, other.nonce())).isFalse();
        assertThat(service.verify(issued.state(), IdentityProvider.GOOGLE, null)).isFalse();
    }

    @Test
    void rejectsAStateIssuedForAnotherProvider() {
        var issued = service.issue(IdentityProvider.GITHUB);
        assertThat(service.verify(issued.state(), IdentityProvider.GOOGLE, issued.nonce())).isFalse();
    }

    @Test
    void rejectsATamperedState() {
        var issued = service.issue(IdentityProvider.LINKEDIN);
        String tampered = issued.state().substring(0, issued.state().length() - 2) + "xx";
        assertThat(service.verify(tampered, IdentityProvider.LINKEDIN, issued.nonce())).isFalse();
        assertThat(service.verify("not-a-token", IdentityProvider.LINKEDIN, issued.nonce())).isFalse();
    }

    @Test
    void rejectsAnExpiredState() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var issuer = new OAuthStateService(PROPS, Clock.fixed(start, ZoneOffset.UTC));
        var issued = issuer.issue(IdentityProvider.GOOGLE);

        var later =
                new OAuthStateService(
                        PROPS,
                        Clock.fixed(start.plus(OAuthStateService.TTL).plusSeconds(5), ZoneOffset.UTC));
        assertThat(later.verify(issued.state(), IdentityProvider.GOOGLE, issued.nonce())).isFalse();
    }

    @Test
    void aStateIsNeverAcceptedAsASessionToken() {
        var issued = service.issue(IdentityProvider.GOOGLE);
        JwtService jwt = new JwtService(PROPS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jwt.parse(issued.state()))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
