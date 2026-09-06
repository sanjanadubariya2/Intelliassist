package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Payload of the {@code metadata} event, sent before the first token so a UI
 * can render provenance while the answer is still arriving.
 *
 * <p>Note the fields that are read but never forwarded to a browser:
 * {@code knowledgeBaseId} and each source's {@code chunkId} are internal to the
 * AI service. See {@code StreamEventSanitizer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiStreamMetadata(
        String queryId, String knowledgeBaseId, Boolean grounded, String model, List<Source> sources) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(
            int marker,
            String chunkId,
            String documentId,
            String filename,
            Integer pageNumber,
            Double score) {}

    public List<Source> sourcesOrEmpty() {
        return sources == null ? List.of() : sources;
    }
}
