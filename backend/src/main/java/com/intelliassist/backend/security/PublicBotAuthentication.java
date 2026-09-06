package com.intelliassist.backend.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The principal for an anonymous widget visitor.
 *
 * <p><strong>Deliberately not an {@link AuthenticatedUser}.</strong> The two are
 * different kinds of caller and must stay distinguishable in the type system: a
 * widget visitor is a browser on a customer's website with no account, no
 * identity and no dashboard rights. If both were the same type, a controller
 * accepting {@code @AuthenticationPrincipal AuthenticatedUser} would silently
 * accept widget traffic — a privilege escalation created by a convenience.
 *
 * <p>Carries the authority {@code ROLE_PUBLIC_BOT}, which no dashboard route
 * accepts, so the separation is enforced by the filter chain as well as by
 * types.
 *
 * @param botId          the bot this key belongs to; never taken from the request
 * @param organizationId owning tenant, for isolation checks
 * @param apiKeyId       which key was used, for auditing and revocation
 */
public record PublicBotAuthentication(UUID botId, UUID organizationId, UUID apiKeyId) {

    public static final String AUTHORITY = "ROLE_PUBLIC_BOT";

    /** Adapts this into the token Spring Security stores in its context. */
    public AbstractAuthenticationToken toToken() {
        return new Token(this);
    }

    /** Authenticated by construction: the filter only builds one after a valid key. */
    public static final class Token extends AbstractAuthenticationToken {

        private final PublicBotAuthentication principal;

        private Token(PublicBotAuthentication principal) {
            super(authorities());
            this.principal = principal;
            setAuthenticated(true);
        }

        private static Collection<GrantedAuthority> authorities() {
            return List.of(new SimpleGrantedAuthority(AUTHORITY));
        }

        @Override
        public PublicBotAuthentication getPrincipal() {
            return principal;
        }

        /** No credential is retained after authentication. */
        @Override
        public Object getCredentials() {
            return null;
        }
    }
}
