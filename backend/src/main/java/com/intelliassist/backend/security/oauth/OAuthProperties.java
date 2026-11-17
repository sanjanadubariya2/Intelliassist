package com.intelliassist.backend.security.oauth;

import com.intelliassist.backend.entity.IdentityProvider;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single sign-on settings.
 *
 * <p>A provider is offered only when both its client id and secret are set, so
 * a deployment that configures none still starts and simply shows no working
 * provider buttons.
 *
 * @param frontendUrl where the dashboard lives; the browser is sent back here
 * @param publicUrl   this backend's externally reachable base URL, used to
 *                    build the callback address registered with each provider
 * @param providers   per-provider credentials, keyed by lowercase id
 *                    ({@code google}, {@code github}, {@code linkedin})
 */
@ConfigurationProperties(prefix = "intelliassist.oauth")
public record OAuthProperties(
        String frontendUrl, String publicUrl, Map<String, Client> providers) {

    public OAuthProperties {
        frontendUrl = stripSlash(frontendUrl != null ? frontendUrl : "http://localhost:3000");
        publicUrl = stripSlash(publicUrl != null ? publicUrl : "http://localhost:8080");
        providers = providers != null ? Map.copyOf(providers) : Map.of();
    }

    /** The client for a provider, only if it is fully configured. */
    public Optional<Client> client(IdentityProvider provider) {
        return Optional.ofNullable(providers.get(provider.id())).filter(Client::configured);
    }

    /** The callback address for a provider, exactly as it must be registered there. */
    public String callbackUrl(IdentityProvider provider) {
        return publicUrl + "/api/v1/auth/oauth/" + provider.id() + "/callback";
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * One provider's OAuth client.
     *
     * <p>The endpoint URLs default to the provider's real ones (see
     * {@link ProviderEndpoints}) and exist as settings only so tests can point
     * them at a local stub.
     */
    public record Client(
            String clientId,
            String clientSecret,
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String emailsUrl) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }

        /** Never print the secret. */
        @Override
        public String toString() {
            return "Client[clientId=%s, clientSecret=***]".formatted(clientId);
        }
    }
}
