package com.intelliassist.backend.dto.publicapi;

/**
 * The one and only time a raw key is returned.
 *
 * <p>Only the hash is stored, so this value cannot be recovered later. The
 * {@code warning} is part of the payload rather than left to documentation,
 * because a client that ignores it leaves the customer unable to use the key
 * they just made.
 */
public record CreatedApiKeyResponse(ApiKeyResponse key, String rawKey, String warning) {

    private static final String WARNING =
            "Copy this key now. It is stored only as a hash and cannot be shown again.";

    public static CreatedApiKeyResponse of(ApiKeyResponse key, String rawKey) {
        return new CreatedApiKeyResponse(key, rawKey, WARNING);
    }
}
