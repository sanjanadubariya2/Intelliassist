package com.intelliassist.backend.dto.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param domain a hostname; "https://example.com" and "example.com" are both
 *               accepted and normalised to the same stored value
 */
public record AddAllowedDomainRequest(
        @NotBlank(message = "Domain is required")
        @Size(max = 253, message = "Domain must be at most 253 characters")
        String domain) {}
