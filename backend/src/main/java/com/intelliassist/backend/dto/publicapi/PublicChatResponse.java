package com.intelliassist.backend.dto.publicapi;

import com.intelliassist.backend.dto.chat.CitationResponse;
import com.intelliassist.backend.dto.chat.MessageResponse;
import java.util.List;
import java.util.UUID;

/**
 * An answer for a website visitor.
 *
 * <p>Deliberately narrower than the dashboard's {@code MessageResponse}. Three
 * fields are dropped:
 *
 * <ul>
 *   <li>{@code model} — a visitor has no reason to learn which LLM vendor the
 *       customer's supplier uses.
 *   <li>{@code latencyMs} — operational detail.
 *   <li>{@code conversationId} on the user echo — redundant, it is already here.
 * </ul>
 *
 * <p>Citations stay: showing a visitor which document an answer came from is
 * the whole point of a grounded assistant.
 */
public record PublicChatResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<CitationResponse> citations,
        boolean grounded) {

    public static PublicChatResponse from(UUID conversationId, MessageResponse assistant) {
        return new PublicChatResponse(
                conversationId,
                assistant.id(),
                assistant.content(),
                assistant.citations(),
                Boolean.TRUE.equals(assistant.grounded()));
    }
}
