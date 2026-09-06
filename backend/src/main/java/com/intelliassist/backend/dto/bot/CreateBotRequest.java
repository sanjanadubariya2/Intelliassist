package com.intelliassist.backend.dto.bot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param name            unique within the organization
 * @param description     optional, shown in the dashboard
 * @param defaultLanguage BCP-47-ish tag, e.g. "en" or "en-GB"
 * @param systemPrompt    reserved; stored but not sent to the AI service today
 */
public record CreateBotRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @Pattern(
                regexp = "^[a-z]{2}(-[A-Z]{2})?$",
                message = "Language must look like 'en' or 'en-GB'")
        String defaultLanguage,

        @Size(max = 4000, message = "System prompt must be at most 4000 characters")
        String systemPrompt) {

    /** Applied when the client omits the language entirely. */
    public String defaultLanguageOrFallback() {
        return defaultLanguage == null || defaultLanguage.isBlank() ? "en" : defaultLanguage;
    }
}
