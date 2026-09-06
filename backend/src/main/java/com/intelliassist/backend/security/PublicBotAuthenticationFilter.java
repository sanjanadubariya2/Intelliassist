package com.intelliassist.backend.security;

import com.intelliassist.backend.service.PublicAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates widget traffic with a bot API key.
 *
 * <p>Runs only on {@code /api/v1/public/**}. Everything else is the dashboard's
 * JWT filter, and neither credential works on the other's routes.
 *
 * <p>Two checks, and both must pass:
 *
 * <ol>
 *   <li><strong>The key.</strong> Looked up by SHA-256 hash, and must not be
 *       revoked.
 *   <li><strong>The origin.</strong> A key sits in a public web page where
 *       anyone can read it, so on its own it proves very little. Requiring the
 *       request to come from a registered domain means a stolen key is only
 *       usable from the customer's own site.
 * </ol>
 *
 * <p>The bot is taken from the key, never from the request. A client cannot ask
 * to talk to a different bot than the one its key belongs to.
 */
@Component
public class PublicBotAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicBotAuthenticationFilter.class);

    public static final String API_KEY_HEADER = "X-Bot-Key";
    private static final String PUBLIC_PREFIX = "/api/v1/public/";

    private final PublicAccessService publicAccessService;

    public PublicBotAuthenticationFilter(PublicAccessService publicAccessService) {
        this.publicAccessService = publicAccessService;
    }

    /** Only public routes; the dashboard chain is untouched. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String rawKey = request.getHeader(API_KEY_HEADER);

        // No key, or an obviously malformed one: leave the context empty and
        // let the entry point produce a 401. Never reaches the database.
        if (!ApiKeyHasher.looksLikeKey(rawKey)) {
            chain.doFilter(request, response);
            return;
        }

        // A browser sends Origin on cross-site requests; Referer is the
        // fallback for the handful of cases where it does not.
        String origin =
                Optional.ofNullable(request.getHeader("Origin"))
                        .orElseGet(() -> request.getHeader("Referer"));

        Optional<PublicBotAuthentication> authenticated =
                publicAccessService.authenticate(rawKey, origin);

        if (authenticated.isEmpty()) {
            // No detail is logged about which check failed: the request is
            // unauthenticated, and saying more would help someone probing.
            log.debug("public request rejected for {}", request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        var token = authenticated.get().toToken();
        token.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(token);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled; a leftover context would leak this bot's
            // identity into an unrelated later request.
            SecurityContextHolder.clearContext();
        }
    }
}
