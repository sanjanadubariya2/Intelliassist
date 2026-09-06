package com.intelliassist.backend.dto.auth;

import com.intelliassist.backend.entity.MemberRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of an organization, as the Members screen needs it.
 *
 * <p>A projection rather than the entity: the screen wants a person's name,
 * email and role, and returning {@code OrganizationMember} would drag the
 * whole {@code User} — password hash included — to the edge of the service.
 */
public record MemberResponse(
        UUID userId, String name, String email, MemberRole role, Instant joinedAt) {}
