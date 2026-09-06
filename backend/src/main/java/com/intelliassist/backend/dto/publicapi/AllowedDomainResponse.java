package com.intelliassist.backend.dto.publicapi;

import com.intelliassist.backend.entity.AllowedDomain;
import java.time.Instant;
import java.util.UUID;

public record AllowedDomainResponse(UUID id, UUID botId, String domain, Instant createdAt) {

    public static AllowedDomainResponse from(AllowedDomain domain) {
        return new AllowedDomainResponse(
                domain.getId(), domain.getBot().getId(), domain.getDomain(), domain.getCreatedAt());
    }
}
