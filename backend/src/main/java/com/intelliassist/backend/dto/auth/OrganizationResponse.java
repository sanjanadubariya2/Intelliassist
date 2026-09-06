package com.intelliassist.backend.dto.auth;

import com.intelliassist.backend.entity.Organization;
import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(UUID id, String name, String slug, Instant createdAt) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getSlug(),
                organization.getCreatedAt());
    }
}
