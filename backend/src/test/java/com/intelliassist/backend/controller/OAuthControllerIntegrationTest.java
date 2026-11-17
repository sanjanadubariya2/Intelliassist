package com.intelliassist.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.AbstractIntegrationTest;
import com.intelliassist.backend.entity.IdentityProvider;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import com.intelliassist.backend.repository.OrganizationRepository;
import com.intelliassist.backend.repository.UserIdentityRepository;
import com.intelliassist.backend.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Single sign-on end to end, with a stub standing in for Google.
 *
 * <p>GitHub is configured too, so the providers list shows a mix; LinkedIn is
 * left unconfigured to prove an unconfigured provider is reported and refused.
 */
@AutoConfigureMockMvc
class OAuthControllerIntegrationTest extends AbstractIntegrationTest {

    private static final MockWebServer PROVIDER = new MockWebServer();
    private static final String FRONTEND = "http://localhost:3000";

    static {
        try {
            PROVIDER.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        String base = PROVIDER.url("/").toString();
        registry.add("intelliassist.oauth.frontend-url", () -> FRONTEND);
        registry.add("intelliassist.oauth.public-url", () -> "http://localhost:8080");
        registry.add("intelliassist.oauth.providers.google.client-id", () -> "google-client");
        registry.add("intelliassist.oauth.providers.google.client-secret", () -> "google-secret");
        registry.add("intelliassist.oauth.providers.google.authorize-url", () -> base + "authorize");
        registry.add("intelliassist.oauth.providers.google.token-url", () -> base + "token");
        registry.add("intelliassist.oauth.providers.google.user-info-url", () -> base + "userinfo");
        registry.add("intelliassist.oauth.providers.github.client-id", () -> "github-client");
        registry.add("intelliassist.oauth.providers.github.client-secret", () -> "github-secret");
    }

    @AfterAll
    static void stopProvider() throws IOException {
        PROVIDER.shutdown();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserIdentityRepository identityRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationMemberRepository memberRepository;

    @BeforeEach
    void clean() {
        identityRepository.deleteAll();
        memberRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    // ------------------------------------------------------------- helpers --

    private record Started(String state, Cookie nonce) {}

    private Started start(String provider) throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/auth/oauth/{p}/start", provider))
                        .andExpect(status().isFound())
                        .andReturn()
                        .getResponse();
        String state =
                UriComponentsBuilder.fromUriString(response.getRedirectedUrl())
                        .build()
                        .getQueryParams()
                        .getFirst("state");
        Cookie nonce = response.getCookie(OAuthController.NONCE_COOKIE);
        assertThat(nonce).isNotNull();
        assertThat(nonce.isHttpOnly()).isTrue();
        return new Started(state, nonce);
    }

    private void enqueueGoogle(String sub, String email, boolean verified, String name) {
        PROVIDER.enqueue(json("{\"access_token\":\"provider-token\",\"token_type\":\"Bearer\"}"));
        PROVIDER.enqueue(
                json(
                        objectMapperWrite(
                                Map.of(
                                        "sub", sub,
                                        "email", email,
                                        "email_verified", verified,
                                        "name", name))));
    }

    private String objectMapperWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private String callback(Started started) throws Exception {
        return mockMvc.perform(
                        get("/api/v1/auth/oauth/google/callback")
                                .param("code", "auth-code")
                                .param("state", started.state())
                                .cookie(started.nonce()))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    // --------------------------------------------------------------- tests --

    @Test
    void listsProvidersWithWhetherEachIsConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].id").value("google"))
                .andExpect(jsonPath("$.providers[0].enabled").value(true))
                .andExpect(jsonPath("$.providers[1].id").value("github"))
                .andExpect(jsonPath("$.providers[1].enabled").value(true))
                .andExpect(jsonPath("$.providers[2].id").value("linkedin"))
                .andExpect(jsonPath("$.providers[2].enabled").value(false));
    }

    @Test
    void startRedirectsToTheProviderWithTheRegisteredCallback() throws Exception {
        String location =
                mockMvc.perform(get("/api/v1/auth/oauth/google/start"))
                        .andExpect(status().isFound())
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();

        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(location).startsWith(PROVIDER.url("/authorize").toString());
        assertThat(params.getFirst("client_id")).isEqualTo("google-client");
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(URLDecoder.decode(params.getFirst("redirect_uri"), StandardCharsets.UTF_8))
                .isEqualTo("http://localhost:8080/api/v1/auth/oauth/google/callback");
        assertThat(params.getFirst("state")).isNotBlank();
    }

    @Test
    void anUnconfiguredProviderSendsTheBrowserBackToLogin() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/linkedin/start"))
                .andExpect(status().isFound())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getRedirectedUrl())
                                        .isEqualTo(FRONTEND + "/login?sso_error=provider_not_configured"));
    }

    @Test
    void firstSignInCreatesAUserWithAWorkspaceAndNoPassword() throws Exception {
        enqueueGoogle("g-1", "Ada@Example.com", true, "Ada Lovelace");

        String location = callback(start("google"));

        assertThat(location).startsWith(FRONTEND + "/auth/callback#token=");
        var user = userRepository.findByEmail("ada@example.com").orElseThrow();
        assertThat(user.getName()).isEqualTo("Ada Lovelace");
        assertThat(user.getPasswordHash()).isNull();
        var memberships = memberRepository.findAllByUserId(user.getId());
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(memberships.get(0).getOrganization().getName()).isEqualTo("Ada's workspace");
        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, "g-1"))
                .isPresent();

        // The issued token is a working session.
        String token = location.substring(location.indexOf("token=") + "token=".length());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("ada@example.com"));
    }

    @Test
    void signingInAgainReusesTheSameAccount() throws Exception {
        enqueueGoogle("g-2", "grace@example.com", true, "Grace Hopper");
        callback(start("google"));
        enqueueGoogle("g-2", "grace@example.com", true, "Grace Hopper");
        callback(start("google"));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(organizationRepository.count()).isEqualTo(1);
    }

    @Test
    void anUnverifiedEmailIsRefused() throws Exception {
        enqueueGoogle("g-3", "who@example.com", false, "Who");

        String location = callback(start("google"));

        assertThat(location).isEqualTo(FRONTEND + "/login?sso_error=no_verified_email");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void aPasswordAccountWithTheSameEmailIsNotTakenOver() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of(
                                                        "name", "Squatter",
                                                        "email", "victim@example.com",
                                                        "password", "correct-horse-battery",
                                                        "organizationName", "Squat Co"))))
                .andExpect(status().isCreated());
        enqueueGoogle("g-4", "victim@example.com", true, "Victim");

        String location = callback(start("google"));

        assertThat(location).isEqualTo(FRONTEND + "/login?sso_error=account_exists");
        assertThat(identityRepository.count()).isZero();
    }

    @Test
    void aForgedOrMismatchedStateIsRejected() throws Exception {
        Started started = start("google");

        mockMvc.perform(
                        get("/api/v1/auth/oauth/google/callback")
                                .param("code", "auth-code")
                                .param("state", started.state())
                                .cookie(new Cookie(OAuthController.NONCE_COOKIE, "someone-else")))
                .andExpect(status().isFound())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getRedirectedUrl())
                                        .isEqualTo(FRONTEND + "/login?sso_error=invalid_state"));
    }

    @Test
    void cancellingAtTheProviderReturnsToLogin() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/google/callback").param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getRedirectedUrl())
                                        .isEqualTo(FRONTEND + "/login?sso_error=access_denied"));
    }

    @Test
    void aProviderOnlyAccountCannotSignInWithAPassword() throws Exception {
        enqueueGoogle("g-5", "sso@example.com", true, "Sso Only");
        callback(start("google"));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                Map.of("email", "sso@example.com", "password", "anything-at-all"))))
                .andExpect(status().isUnauthorized());
    }
}
