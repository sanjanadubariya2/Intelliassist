package com.intelliassist.backend.dto.bot;

import com.intelliassist.backend.entity.BotStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A full replacement, matching PUT semantics: every field is sent, and an
 * omitted optional field clears it.
 *
 * <p>The organization is deliberately absent. A bot cannot be moved between
 * tenants, and accepting an organization id from the client would be the exact
 * shape of a broken-access-control bug.
 */
public record UpdateBotRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @NotNull(message = "Status is required")
        BotStatus status,

        @Pattern(
                regexp = "^[a-z]{2}(-[A-Z]{2})?$",
                message = "Language must look like 'en' or 'en-GB'")
        String defaultLanguage,

        @Size(max = 4000, message = "System prompt must be at most 4000 characters")
        String systemPrompt) {

    public String defaultLanguageOrFallback() {
        return defaultLanguage == null || defaultLanguage.isBlank() ? "en" : defaultLanguage;
    }
}
