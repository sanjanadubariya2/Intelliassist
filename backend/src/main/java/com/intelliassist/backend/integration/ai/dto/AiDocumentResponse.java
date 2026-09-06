package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

/**
 * Ingestion status.
 *
 * <p>Lifecycle: pending, extracting, chunking, embedding, then indexed or
 * failed. A document is only queryable at {@code indexed}, and {@code failed}
 * always carries {@code errorMessage}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiDocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String filename,
        String mimeType,
        String status,
        Long byteSize,
        Integer pageCount,
        Integer chunkCount,
        String errorMessage,
        Instant createdAt,
        Instant indexedAt) {}
