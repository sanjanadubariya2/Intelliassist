package com.intelliassist.backend.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A question for the bot.
 *
 * <p>The 4000-character ceiling matches the AI service's own limit on
 * {@code query}, so an over-long message is rejected here rather than after a
 * round trip.
 */
public record SendMessageRequest(
        @NotBlank(message = "Message content is required")
        @Size(max = 4000, message = "Message must be at most 4000 characters")
        String content) {}
