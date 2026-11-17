package com.intelliassist.backend.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelliassist.backend.entity.IdentityProvider;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The two server-to-server calls of the authorization-code flow: exchange the
 * code for an access token, then read who it belongs to.
 *
 * <p>The access token is used once and dropped. Nothing here keeps a provider
 * token, because the product never acts on a provider's API on the user's
 * behalf; it only needs to know who they are.
 */
@Component
public class OAuthClient {

    private final RestClient http;

    public OAuthClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // A provider that hangs must not hold a request thread for long.
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.requestFactory(factory).build();
    }

    /** Exchanges an authorization code for an access token. */
    public String exchangeCode(
            ProviderEndpoints endpoints,
            OAuthProperties.Client client,
            String code,
            String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());

        JsonNode body =
                call(
                        () ->
                                http.post()
                                        .uri(endpoints.tokenUrl())
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        // GitHub answers form-encoded unless asked for JSON.
                                        .accept(MediaType.APPLICATION_JSON)
                                        .body(form)
                                        .retrieve()
                                        .body(JsonNode.class));

        String token = text(body, "access_token");
        if (token == null) {
            // GitHub reports a bad code with 200 and an "error" field.
            throw new OAuthException("provider_error", "The provider did not return an access token");
        }
        return token;
    }

    /** Reads the signed-in account's profile. */
    public OAuthProfile fetchProfile(
            IdentityProvider provider, ProviderEndpoints endpoints, String accessToken) {
        JsonNode info = get(endpoints.userInfoUrl(), accessToken);

        return switch (provider) {
            // OpenID Connect userinfo: sub, email, email_verified, name.
            case GOOGLE, LINKEDIN ->
                    new OAuthProfile(
                            provider,
                            required(info, "sub"),
                            text(info, "email"),
                            info != null && info.path("email_verified").asBoolean(false),
                            text(info, "name"));
            case GITHUB -> githubProfile(endpoints, accessToken, info);
        };
    }

    /**
     * GitHub's {@code /user} may show no email at all when the user keeps it
     * private, so the address comes from {@code /user/emails}: the primary one,
     * and only if GitHub has verified it.
     */
    private OAuthProfile githubProfile(ProviderEndpoints endpoints, String accessToken, JsonNode user) {
        String subject = required(user, "id");
        String name = text(user, "name");
        if (name == null) {
            name = text(user, "login");
        }

        String email = null;
        boolean verified = false;
        JsonNode emails = get(endpoints.emailsUrl(), accessToken);
        if (emails != null && emails.isArray()) {
            for (JsonNode entry : emails) {
                if (entry.path("primary").asBoolean(false)) {
                    email = text(entry, "email");
                    verified = entry.path("verified").asBoolean(false);
                    break;
                }
            }
        }
        return new OAuthProfile(IdentityProvider.GITHUB, subject, email, verified, name);
    }

    private JsonNode get(String url, String accessToken) {
        return call(
                () ->
                        http.get()
                                .uri(url)
                                .headers(headers -> headers.setBearerAuth(accessToken))
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .body(JsonNode.class));
    }

    private static JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (RestClientException exception) {
            throw new OAuthException("provider_error", "Could not reach the sign-in provider");
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new OAuthException("provider_error", "The provider did not identify the account");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
