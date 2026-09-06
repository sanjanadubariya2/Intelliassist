package com.intelliassist.backend.dto.auth;

import com.intelliassist.backend.entity.MemberRole;
import java.time.Instant;

/**
 * Returned by register and login.
 *
 * <p>Includes the user, organization and role alongside the token so the
 * dashboard can render immediately without a follow-up call to {@code /me}.
 *
 * @param token     bearer token for the Authorization header
 * @param expiresAt when the token stops working, so a client can refresh early
 */
public record AuthResponse(
        String token,
        Instant expiresAt,
        UserResponse user,
        OrganizationResponse organization,
        MemberRole role) {

    /** The token is a live credential; keep it out of logs. */
    @Override
    public String toString() {
        return "AuthResponse[token=***, expiresAt=%s, user=%s]".formatted(expiresAt, user.id());
    }
}
