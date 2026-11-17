package com.intelliassist.backend.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelliassist.backend.entity.IdentityProvider;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The provider calls, against a stub server that answers like each provider. */
class OAuthClientTest {

    private MockWebServer server;
    private OAuthClient client;
    private final OAuthProperties.Client credentials =
            new OAuthProperties.Client("client-id", "client-secret", null, null, null, null);

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OAuthClient(RestClient.builder());
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ProviderEndpoints endpoints() {
        String base = server.url("/").toString();
        return new ProviderEndpoints(
                base + "authorize", base + "token", base + "user", base + "emails", "scope");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void exchangesTheCodeWithTheClientCredentials() throws Exception {
        server.enqueue(json("{\"access_token\":\"abc\",\"token_type\":\"bearer\"}"));

        String token = client.exchangeCode(endpoints(), credentials, "the-code", "http://cb");

        assertThat(token).isEqualTo("abc");
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body)
                .contains("grant_type=authorization_code")
                .contains("code=the-code")
                .contains("client_id=client-id")
                .contains("client_secret=client-secret");
        assertThat(request.getHeader("Accept")).contains("application/json");
    }

    @Test
    void aTokenResponseWithoutATokenIsAnError() {
        // GitHub's answer to a bad or reused code: 200 with an error field.
        server.enqueue(json("{\"error\":\"bad_verification_code\"}"));

        assertThatThrownBy(() -> client.exchangeCode(endpoints(), credentials, "x", "http://cb"))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).code())
                .isEqualTo("provider_error");
    }

    @Test
    void readsAnOpenIdConnectProfile() throws Exception {
        server.enqueue(
                json(
                        "{\"sub\":\"g-123\",\"email\":\"Ada@Example.com\","
                                + "\"email_verified\":true,\"name\":\"Ada Lovelace\"}"));

        OAuthProfile profile = client.fetchProfile(IdentityProvider.GOOGLE, endpoints(), "abc");

        assertThat(profile.subject()).isEqualTo("g-123");
        assertThat(profile.email()).isEqualTo("Ada@Example.com");
        assertThat(profile.emailVerified()).isTrue();
        assertThat(profile.name()).isEqualTo("Ada Lovelace");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer abc");
    }

    @Test
    void anUnverifiedOpenIdConnectEmailIsReportedAsSuch() {
        server.enqueue(json("{\"sub\":\"l-1\",\"email\":\"x@y.com\",\"email_verified\":false}"));

        OAuthProfile profile = client.fetchProfile(IdentityProvider.LINKEDIN, endpoints(), "abc");

        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void takesGithubsPrimaryVerifiedEmailFromTheEmailsEndpoint() {
        // A private email: /user shows none, /user/emails has it.
        server.enqueue(json("{\"id\":42,\"login\":\"ada\",\"name\":null,\"email\":null}"));
        server.enqueue(
                json(
                        "[{\"email\":\"old@example.com\",\"primary\":false,\"verified\":true},"
                                + "{\"email\":\"ada@example.com\",\"primary\":true,\"verified\":true}]"));

        OAuthProfile profile = client.fetchProfile(IdentityProvider.GITHUB, endpoints(), "abc");

        assertThat(profile.subject()).isEqualTo("42");
        assertThat(profile.email()).isEqualTo("ada@example.com");
        assertThat(profile.emailVerified()).isTrue();
        // Falls back to the login when no display name is set.
        assertThat(profile.name()).isEqualTo("ada");
    }

    @Test
    void anUnverifiedGithubPrimaryEmailIsNotVerified() {
        server.enqueue(json("{\"id\":7,\"login\":\"x\"}"));
        server.enqueue(json("[{\"email\":\"x@example.com\",\"primary\":true,\"verified\":false}]"));

        OAuthProfile profile = client.fetchProfile(IdentityProvider.GITHUB, endpoints(), "abc");

        assertThat(profile.emailVerified()).isFalse();
    }

    @Test
    void aProviderErrorStatusBecomesAProviderError() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client.fetchProfile(IdentityProvider.GOOGLE, endpoints(), "abc"))
                .isInstanceOf(OAuthException.class);
    }
}
