package com.intelliassist.backend.security.oauth;

import com.intelliassist.backend.entity.IdentityProvider;

/**
 * Who the provider says signed in.
 *
 * @param subject       the provider's stable id for the account
 * @param email         may be {@code null} if the provider has none to share
 * @param emailVerified whether the provider vouches that the address is theirs
 * @param name          display name; may be {@code null}
 */
public record OAuthProfile(
        IdentityProvider provider,
        String subject,
        String email,
        boolean emailVerified,
        String name) {

    /** No personal data in logs. */
    @Override
    public String toString() {
        return "OAuthProfile[provider=%s, emailVerified=%s]".formatted(provider, emailVerified);
    }
}
