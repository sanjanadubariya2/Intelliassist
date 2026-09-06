package com.intelliassist.backend.dto.publicapi;

import com.intelliassist.backend.entity.ApiKey;
import java.time.Instant;
import java.util.UUID;

/**
 * An API key as listed in the dashboard.
 *
 * <p>Contains no usable credential: only the non-secret prefix, enough to tell
 * one key from another. The raw key appears exactly once, in
 * {@link CreatedApiKeyResponse}, and can never be retrieved again.
 */
public record ApiKeyResponse(
        UUID id,
        UUID botId,
        String name,
        String keyPrefix,
        boolean active,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt) {

    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getBot().getId(),
                apiKey.getName(),
                apiKey.getKeyPrefix(),
                apiKey.isActive(),
                apiKey.getLastUsedAt(),
                apiKey.getRevokedAt(),
                apiKey.getCreatedAt());
    }
}
