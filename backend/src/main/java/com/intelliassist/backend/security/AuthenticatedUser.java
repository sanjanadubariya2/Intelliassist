package com.intelliassist.backend.security;

import com.intelliassist.backend.entity.MemberRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated caller, as every service below the web layer sees them.
 *
 * <p>Carries the three facts authorization needs: who they are, which
 * organization they are acting in, and their role there. Services take this
 * rather than reaching into the {@code SecurityContext}, which keeps them
 * testable with a plain constructor call.
 *
 * <p>The role is resolved from the database on each request, not read from the
 * token, so revoking a membership or demoting a user takes effect immediately.
 *
 * @param userId         authenticated user
 * @param organizationId the tenant this request acts within
 * @param role           the user's role in that organization
 * @param email          convenience for logging and {@code /me}; never in the token
 */
public record AuthenticatedUser(
        UUID userId, UUID organizationId, MemberRole role, String email) implements UserDetails {

    /** True when the caller's role is at least {@code required}. */
    public boolean hasAtLeast(MemberRole required) {
        return role.atLeast(required);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /**
     * Always null.
     *
     * <p>The password hash is never loaded into the security context. Nothing
     * after authentication needs it, and not having it means it cannot leak.
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return userId.toString();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** Deliberately omits the email, which is personal data. */
    @Override
    public String toString() {
        return "AuthenticatedUser[userId=%s, organizationId=%s, role=%s]"
                .formatted(userId, organizationId, role);
    }
}
