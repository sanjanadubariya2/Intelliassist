package com.intelliassist.backend.dto.knowledge;

import com.intelliassist.backend.entity.KnowledgeBase;
import java.time.Instant;
import java.util.UUID;

/**
 * A knowledge base as the dashboard sees it.
 *
 * <p>{@code aiKnowledgeBaseId} is deliberately absent. It is an internal
 * coupling to a private service, and exposing it would invite a frontend to
 * start using it - the browser must never address the AI service at all.
 */
public record KnowledgeBaseResponse(
        UUID id, UUID botId, String name, long documentCount, Instant createdAt) {

    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase, long documentCount) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getBot().getId(),
                knowledgeBase.getName(),
                documentCount,
                knowledgeBase.getCreatedAt());
    }
}
