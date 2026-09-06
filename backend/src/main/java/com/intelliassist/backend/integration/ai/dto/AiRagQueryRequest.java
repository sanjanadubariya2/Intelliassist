package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/rag/query}.
 *
 * <p>The service still holds no state between calls. History is passed per
 * request, purely so a follow-up can be rewritten into a standalone search
 * query before retrieval; the AI service stores none of it. Omit it and the
 * query is used exactly as sent, which is the original behaviour.
 *
 * <p>Note what the contract still does <em>not</em> accept: a per-request
 * system prompt.
 *
 * <p>Nulls are omitted so optional fields fall back to the AI service's own
 * configured defaults rather than being overridden with null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiRagQueryRequest(
        UUID knowledgeBaseId,
        String query,
        Integer topN,
        List<UUID> documentIds,
        Boolean includeSources,
        List<AiConversationTurn> history) {

    /** A standalone question, with no conversational context. */
    public static AiRagQueryRequest of(UUID knowledgeBaseId, String query) {
        return new AiRagQueryRequest(knowledgeBaseId, query, null, null, true, null);
    }

    /**
     * A question with the conversation that preceded it.
     *
     * <p>An empty list is sent as null so the AI service takes its "no
     * history" path rather than doing the work of finding nothing to resolve.
     */
    public static AiRagQueryRequest of(
            UUID knowledgeBaseId, String query, List<AiConversationTurn> history) {
        return new AiRagQueryRequest(
                knowledgeBaseId,
                query,
                null,
                null,
                true,
                history == null || history.isEmpty() ? null : history);
    }
}
