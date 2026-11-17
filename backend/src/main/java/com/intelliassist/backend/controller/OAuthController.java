package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.auth.AuthResponse;
import com.intelliassist.backend.entity.IdentityProvider;
import com.intelliassist.backend.security.oauth.OAuthClient;
import com.intelliassist.backend.security.oauth.OAuthException;
import com.intelliassist.backend.security.oauth.OAuthProfile;
import com.intelliassist.backend.security.oauth.OAuthProperties;
import com.intelliassist.backend.security.oauth.OAuthStateService;
import com.intelliassist.backend.security.oauth.ProviderEndpoints;
import com.intelliassist.backend.service.AuthService;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Single sign-on with Google, GitHub and LinkedIn (authorization-code flow).
 *
 * <ol>
 *   <li>The login page links the browser to {@code /{provider}/start}, which
 *       redirects to the provider with a signed {@code state} and sets a
 *       matching nonce cookie.
 *   <li>The provider sends the browser back to {@code /{provider}/callback}.
 *       The state and cookie are checked, the code is exchanged, the profile is
 *       read, and the user is found, linked or created.
 *   <li>The browser is sent to the dashboard's {@code /auth/callback} with the
 *       session token in the URL fragment. A fragment never reaches a server,
 *       so the token stays out of access logs and {@code Referer} headers.
 * </ol>
 *
 * <p>Every failure also ends in a redirect, to the login page with a short
 * {@code sso_error} code, because the browser is mid-navigation and a JSON
 * error body would be a blank page.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    static final String NONCE_COOKIE = "ia_oauth_nonce";
    private static final String COOKIE_PATH = "/api/v1/auth/oauth";

    private final OAuthProperties properties;
    private final OAuthStateService stateService;
    private final OAuthClient client;
    private final AuthService authService;

    public OAuthController(
            OAuthProperties properties,
            OAuthStateService stateService,
            OAuthClient client,
            AuthService authService) {
        this.properties = properties;
        this.stateService = stateService;
        this.client = client;
        this.authService = authService;
    }

    /** Every provider and whether this deployment has it configured. */
    @GetMapping("/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(
                Arrays.stream(IdentityProvider.values())
                        .map(
                                provider ->
                                        new ProviderSummary(
                                                provider.id(),
                                                provider.displayName(),
                                                properties.client(provider).isPresent()))
                        .toList());
    }

    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable("provider") String providerId) {
        IdentityProvider provider = IdentityProvider.fromId(providerId).orElse(null);
        if (provider == null) {
            return toLogin("unknown_provider");
        }
        var maybeClient = properties.client(provider);
        if (maybeClient.isEmpty()) {
            return toLogin("provider_not_configured");
        }

        OAuthProperties.Client oauthClient = maybeClient.get();
        ProviderEndpoints endpoints = ProviderEndpoints.resolve(provider, oauthClient);
        OAuthStateService.IssuedState issued = stateService.issue(provider);

        URI authorize =
                UriComponentsBuilder.fromUriString(endpoints.authorizeUrl())
                        .queryParam("response_type", "code")
                        .queryParam("client_id", oauthClient.clientId())
                        .queryParam("redirect_uri", properties.callbackUrl(provider))
                        .queryParam("scope", endpoints.scope())
                        .queryParam("state", issued.state())
                        .encode()
                        .build()
                        .toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authorize)
                .header(
                        HttpHeaders.SET_COOKIE,
                        nonceCookie(issued.nonce(), OAuthStateService.TTL).toString())
                .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @PathVariable("provider") String providerId,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @CookieValue(value = NONCE_COOKIE, required = false) String nonce) {
        IdentityProvider provider = IdentityProvider.fromId(providerId).orElse(null);
        if (provider == null) {
            return toLogin("unknown_provider");
        }
        if (error != null) {
            // Most often the person pressed Cancel on the provider's screen.
            return toLogin("access_denied");
        }
        var maybeClient = properties.client(provider);
        if (maybeClient.isEmpty()) {
            return toLogin("provider_not_configured");
        }
        if (code == null || !stateService.verify(state, provider, nonce)) {
            log.warn("rejected {} callback: invalid or missing state", provider);
            return toLogin("invalid_state");
        }

        try {
            OAuthProperties.Client oauthClient = maybeClient.get();
            ProviderEndpoints endpoints = ProviderEndpoints.resolve(provider, oauthClient);
            String accessToken =
                    client.exchangeCode(endpoints, oauthClient, code, properties.callbackUrl(provider));
            OAuthProfile profile = client.fetchProfile(provider, endpoints, accessToken);
            AuthResponse auth = authService.signInWithProvider(profile);

            URI target =
                    URI.create(properties.frontendUrl() + "/auth/callback#token=" + auth.token());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(target)
                    .header(HttpHeaders.SET_COOKIE, nonceCookie("", Duration.ZERO).toString())
                    .build();
        } catch (OAuthException exception) {
            log.warn("{} sign-in failed: {}", provider, exception.code());
            return toLogin(exception.code());
        }
    }

    private ResponseEntity<Void> toLogin(String code) {
        URI target =
                UriComponentsBuilder.fromUriString(properties.frontendUrl() + "/login")
                        .queryParam("sso_error", code)
                        .build()
                        .toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target)
                .header(HttpHeaders.SET_COOKIE, nonceCookie("", Duration.ZERO).toString())
                .build();
    }

    /**
     * The nonce cookie: HttpOnly so page script cannot read it, and SameSite
     * Lax so it still rides along on the provider's top-level redirect back.
     */
    private ResponseCookie nonceCookie(String value, Duration maxAge) {
        return ResponseCookie.from(NONCE_COOKIE, value)
                .httpOnly(true)
                .secure(properties.publicUrl().startsWith("https://"))
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    public record ProviderSummary(String id, String name, boolean enabled) {}

    public record ProvidersResponse(List<ProviderSummary> providers) {}
}
