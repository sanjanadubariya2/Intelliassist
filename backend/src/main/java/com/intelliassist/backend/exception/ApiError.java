package com.intelliassist.backend.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single error shape returned by every endpoint.
 *
 * <p>One shape for all failures means a client writes one error handler. The
 * {@code requestId} matches the {@code X-Request-ID} response header and the
 * correlation id in the logs, so a user reporting "it failed" can be traced to
 * the exact request.
 *
 * @param timestamp when the failure was produced
 * @param status    HTTP status code
 * @param error     stable machine-readable code, e.g. {@code NOT_FOUND}
 * @param message   human-readable explanation, safe to show a user
 * @param path      request path that failed
 * @param requestId correlation id shared with the logs
 * @param details   field-level validation problems, omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        List<FieldProblem> details) {

    public static ApiError of(
            int status, String error, String message, String path, String requestId) {
        return new ApiError(Instant.now(), status, error, message, path, requestId, List.of());
    }

    public static ApiError validation(
            String message, String path, String requestId, List<FieldProblem> details) {
        return new ApiError(
                Instant.now(), 400, "VALIDATION_FAILED", message, path, requestId, details);
    }

    /**
     * One rejected field.
     *
     * @param field   the offending property
     * @param message why it was rejected
     */
    public record FieldProblem(String field, String message) {

        public static FieldProblem of(Map.Entry<String, String> entry) {
            return new FieldProblem(entry.getKey(), entry.getValue());
        }
    }
}
