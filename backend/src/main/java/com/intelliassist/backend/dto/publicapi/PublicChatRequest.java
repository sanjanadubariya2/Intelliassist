package com.intelliassist.backend.dto.publicapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A visitor's message.
 *
 * <p>No bot id and no organization id. Both come from the API key, so there is
 * no field a client could tamper with to reach another tenant's bot.
 *
 * @param content   the question
 * @param visitorId opaque browser-session identifier, for grouping a visitor's
 *                  messages into one conversation
 */
public record PublicChatRequest(
        @NotBlank(message = "Message content is required")
        @Size(max = 4000, message = "Message must be at most 4000 characters")
        String content,

        @Size(max = 128, message = "Visitor id must be at most 128 characters")
        String visitorId) {}
