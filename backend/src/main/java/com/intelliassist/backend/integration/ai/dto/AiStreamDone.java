package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Payload of the {@code done} event.
 *
 * <p>Boxed types throughout: the ungrounded path emits only {@code grounded}
 * and {@code total_ms}, so the rest are genuinely absent rather than zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiStreamDone(
        Boolean grounded,
        Integer sourcesUsed,
        Integer contextTokens,
        Double retrievalMs,
        Double rerankMs,
        Double generationMs,
        Double totalMs) {

    public boolean groundedOrFalse() {
        return Boolean.TRUE.equals(grounded);
    }
}
