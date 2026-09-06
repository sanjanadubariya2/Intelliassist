package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.intelliassist.backend.entity.MessageRole;

/**
 * One prior message, sent so the AI service can resolve a follow-up.
 *
 * <p>Deliberately just a role and text. The AI service has no notion of a
 * conversation, a visitor or an organization, and giving it one would put a
 * second copy of the tenancy rules somewhere they cannot be enforced.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConversationTurn(String role, String content) {

    public static AiConversationTurn of(MessageRole role, String content) {
        // Lowercase to match the AI service's own literal type.
        return new AiConversationTurn(role.name().toLowerCase(), content);
    }
}
