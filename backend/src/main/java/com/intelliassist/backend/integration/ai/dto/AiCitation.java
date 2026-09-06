package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

/**
 * One source the answer drew on.
 *
 * <p>{@code marker} matches an inline {@code [S1]} in the answer text, which is
 * how a UI turns a claim into a clickable source. {@code documentId} maps back
 * to our own {@code Document.aiDocumentId}, which is how a citation becomes a
 * real filename the user recognises.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiCitation(
        int marker,
        UUID chunkId,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String snippet,
        double score) {}
