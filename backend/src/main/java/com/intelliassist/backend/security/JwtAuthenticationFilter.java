package com.intelliassist.backend.security;

import com.intelliassist.backend.entity.OrganizationMember;
import com.intelliassist.backend.repository.OrganizationMemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a bearer token into an authenticated principal.
 *
 * <p>Two steps, and the second is the one that matters: the token is verified
 * cryptographically, then the membership it claims is <em>confirmed against the
 * database</em>. A token is only a claim about identity; whether that user still
 * belongs to that organization, and with what role, is a fact that lives in the
 * database and can change at any moment.
 *
 * <p>Without the second step, removing someone from an organization would leave
 * them with access until their token expired.
 *
 * <p>The filter never rejects a request itself. It either establishes an
 * authentication or leaves the context empty and lets the chain continue —
 * Spring Security's entry point decides what an anonymous request may reach, so
 * public endpoints keep working.
 *
 * <p>Deliberately not {@code @Transactional}. The annotation would make Spring
 * wrap this filter in a CGLIB proxy, and such a proxy is instantiated without
 * running field initialisers — leaving {@code GenericFilterBean.logger} null and
 * failing on the first request. It is also unnecessary: {@code findMembership}
 * uses {@code JOIN FETCH}, so the user and organization are already loaded when
 * the repository's own transaction returns.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final OrganizationMemberRepository memberRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService, OrganizationMemberRepository memberRepository) {
        this.jwtService = jwtService;
        this.memberRepository = memberRepository;
    }

    /**
     * Never runs on widget routes.
     *
     * <p>Those authenticate with a bot API key, and the two credential systems
     * must not interact: without this, a stray {@code Authorization} header on
     * a widget request would put a dashboard principal in the context on a
     * public path. The authorization rules would still reject it, but relying
     * on a second layer to undo a mistake the first layer made is not a design.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/public/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Already authenticated by something earlier in the chain, or no token
        // offered. Either way there is nothing for this filter to do.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            extractToken(request).ifPresent(token -> authenticate(token, request));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            JwtService.TokenIdentity identity = jwtService.parse(token);

            Optional<OrganizationMember> membership =
                    memberRepository.findMembership(
                            identity.userId(), identity.organizationId());

            if (membership.isEmpty()) {
                // A validly signed token whose membership no longer exists:
                // the user was removed, or the organization was deleted.
                log.warn("token presented for a membership that no longer exists");
                return;
            }

            OrganizationMember member = membership.get();
            AuthenticatedUser principal =
                    new AuthenticatedUser(
                            member.getUser().getId(),
                            member.getOrganization().getId(),
                            member.getRole(),
                            member.getUser().getEmail());

            var authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (Exception exception) {
            // Leave the context anonymous and let the entry point respond. The
            // token is never logged - it is a live credential.
            log.warn("authentication failed: {}", exception.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
        }
    }

    private static Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
