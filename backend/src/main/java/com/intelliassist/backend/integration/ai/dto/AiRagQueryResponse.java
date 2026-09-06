package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.UUID;

/**
 * A grounded answer.
 *
 * <p>Three fields carry meaning that must not be flattened away:
 *
 * <ul>
 *   <li>{@code grounded == false} - retrieval found nothing relevant. The
 *       answer is a fixed refusal, citations are empty, and the model was never
 *       called. This is a normal 200, not an error.
 *   <li>{@code truncated == true} - the model hit its output cap, so the answer
 *       is usable but incomplete and may have lost trailing citation markers.
 *   <li>{@code timings} - per-stage latency, which is what makes a slow query
 *       attributable from our side rather than a mystery.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiRagQueryResponse(
        UUID queryId,
        UUID knowledgeBaseId,
        String query,
        String answer,
        List<AiCitation> citations,
        boolean grounded,
        String model,
        boolean truncated,
        Usage usage,
        Timings timings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(
            int promptTokens,
            int completionTokens,
            int contextTokens,
            int candidatesRetrieved,
            int sourcesUsed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Timings(
            double retrievalMs, double rerankMs, double generationMs, double totalMs) {}

    public List<AiCitation> citationsOrEmpty() {
        return citations == null ? List.of() : citations;
    }

    public int totalTokens() {
        return usage == null ? 0 : usage.promptTokens() + usage.completionTokens();
    }
}
