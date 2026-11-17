package com.intelliassist.backend.security.oauth;

import com.intelliassist.backend.entity.IdentityProvider;

/**
 * Where each provider's OAuth endpoints live, and what to ask for.
 *
 * <p>Google and LinkedIn are OpenID Connect providers, so one standard userinfo
 * call returns a stable {@code sub}, the email and whether it is verified.
 * GitHub is plain OAuth: the profile comes from {@code /user} and the verified
 * address from a separate {@code /user/emails} call.
 *
 * @param emailsUrl only GitHub has one; {@code null} elsewhere
 */
public record ProviderEndpoints(
        String authorizeUrl, String tokenUrl, String userInfoUrl, String emailsUrl, String scope) {

    public static ProviderEndpoints defaults(IdentityProvider provider) {
        return switch (provider) {
            case GOOGLE ->
                    new ProviderEndpoints(
                            "https://accounts.google.com/o/oauth2/v2/auth",
                            "https://oauth2.googleapis.com/token",
                            "https://openidconnect.googleapis.com/v1/userinfo",
                            null,
                            "openid email profile");
            case GITHUB ->
                    new ProviderEndpoints(
                            "https://github.com/login/oauth/authorize",
                            "https://github.com/login/oauth/access_token",
                            "https://api.github.com/user",
                            "https://api.github.com/user/emails",
                            "read:user user:email");
            case LINKEDIN ->
                    new ProviderEndpoints(
                            "https://www.linkedin.com/oauth/v2/authorization",
                            "https://www.linkedin.com/oauth/v2/accessToken",
                            "https://api.linkedin.com/v2/userinfo",
                            null,
                            "openid profile email");
        };
    }

    /** The defaults, with any URL the client configuration overrides. */
    public static ProviderEndpoints resolve(IdentityProvider provider, OAuthProperties.Client client) {
        ProviderEndpoints base = defaults(provider);
        return new ProviderEndpoints(
                orElse(client.authorizeUrl(), base.authorizeUrl()),
                orElse(client.tokenUrl(), base.tokenUrl()),
                orElse(client.userInfoUrl(), base.userInfoUrl()),
                orElse(client.emailsUrl(), base.emailsUrl()),
                base.scope());
    }

    private static String orElse(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
