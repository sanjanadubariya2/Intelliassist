package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.Message;
import com.intelliassist.backend.entity.MessageRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One turn of a conversation.
 *
 * <p>{@code grounded} is surfaced deliberately. When it is false the answer is
 * a fixed refusal and no model was consulted - a UI should present that
 * differently from an answer with sources, because the two mean very different
 * things to someone deciding whether to trust it.
 */
public record MessageResponse(
        UUID id,
        UUID conversationId,
        MessageRole role,
        String content,
        List<CitationResponse> citations,
        Boolean grounded,
        String model,
        Integer latencyMs,
        Instant createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversation().getId(),
                message.getRole(),
                message.getContent(),
                message.getCitations().stream().map(CitationResponse::from).toList(),
                message.getGrounded(),
                message.getModel(),
                message.getLatencyMs(),
                message.getCreatedAt());
    }
}
