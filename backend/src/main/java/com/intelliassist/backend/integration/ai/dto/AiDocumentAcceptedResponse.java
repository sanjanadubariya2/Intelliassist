package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

/**
 * The 202 returned by document upload.
 *
 * <p>{@code duplicate} is a success signal, not an error: the AI service hashes
 * the bytes and returns the document it already has rather than indexing the
 * same corpus twice. Treating it as a failure would make client retries look
 * broken.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiDocumentAcceptedResponse(
        UUID id,
        UUID knowledgeBaseId,
        String filename,
        String status,
        boolean duplicate,
        String pollUrl) {}
