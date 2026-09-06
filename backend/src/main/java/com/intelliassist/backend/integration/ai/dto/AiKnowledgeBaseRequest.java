package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/**
 * Body for {@code POST /api/v1/knowledge-bases}.
 *
 * <p>Snake_case is applied per class rather than by changing the global Jackson
 * strategy, which would also rewrite this application's own public API. The AI
 * service's naming convention is its business, not ours.
 *
 * @param externalRef opaque key the AI service stores but never interprets;
 *                    globally unique, so a duplicate returns 409
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiKnowledgeBaseRequest(
        String externalRef, String name, String description, Map<String, Object> metadata) {

    public static AiKnowledgeBaseRequest of(String externalRef, String name, String description) {
        return new AiKnowledgeBaseRequest(externalRef, name, description, Map.of());
    }
}
