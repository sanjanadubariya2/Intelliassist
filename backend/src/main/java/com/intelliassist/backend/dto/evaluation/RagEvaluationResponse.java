package com.intelliassist.backend.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The latest evaluation for a bot.
 *
 * <p>Metric values may be null, and that is the point: null means the dataset
 * asked nothing about it. A dashboard must render "n/a" there rather than a
 * zero, which would read as a failing score.
 */
public record RagEvaluationResponse(
        UUID id,
        UUID botId,
        String dataset,
        String model,
        Instant startedAt,
        Instant finishedAt,
        int totalQuestions,
        int successful,
        int failed,
        int unmetExpectations,
        Map<String, Double> metrics,
        Map<String, Double> latency,
        JsonNode cases) {}
