package com.intelliassist.backend.dto.auth;

import com.intelliassist.backend.entity.MemberRole;
import java.util.List;

/**
 * The answer to {@code GET /me}: who am I, where am I acting, and what else can
 * I see.
 *
 * @param user          the authenticated user
 * @param organization  the organization this token acts within
 * @param role          their role there, read live from the database
 * @param organizations every organization they belong to, for an org switcher
 */
public record CurrentUserResponse(
        UserResponse user,
        OrganizationResponse organization,
        MemberRole role,
        List<MembershipSummary> organizations) {

    public record MembershipSummary(OrganizationResponse organization, MemberRole role) {}
}
