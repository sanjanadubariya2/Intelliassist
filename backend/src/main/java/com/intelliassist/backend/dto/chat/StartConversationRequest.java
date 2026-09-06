package com.intelliassist.backend.dto.chat;

import jakarta.validation.constraints.Size;

/**
 * @param visitorId opaque, caller-supplied identity for the person talking.
 *                  Optional: a dashboard user testing a bot has none.
 */
public record StartConversationRequest(
        @Size(max = 128, message = "Visitor id must be at most 128 characters")
        String visitorId) {}
