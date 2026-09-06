package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

/**
 * A knowledge base as the AI service reports it.
 *
 * <p>{@code ignoreUnknown} matters: the AI service may add response fields, and
 * a strict binding would turn a harmless addition into a production outage on
 * our side.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiKnowledgeBaseResponse(
        UUID id,
        String externalRef,
        String name,
        String status,
        String embeddingModel,
        Integer embeddingDim,
        Instant createdAt) {}
