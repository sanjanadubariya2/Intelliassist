package com.intelliassist.backend.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/**
 * The AI service's error envelope. Every non-2xx response has this shape.
 *
 * <p>The {@code requestId} is the join key between our logs and theirs, and is
 * the single most useful thing to record when an integration call fails.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiErrorResponse(Detail error) {

    /** Snake_case, so {@code request_id} actually binds. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Detail(String code, String message, Map<String, Object> details, String requestId) {}

    public String codeOrUnknown() {
        return error == null || error.code() == null ? "unknown" : error.code();
    }

    public String messageOrEmpty() {
        return error == null || error.message() == null ? "" : error.message();
    }

    public String requestIdOrNull() {
        return error == null ? null : error.requestId();
    }
}
