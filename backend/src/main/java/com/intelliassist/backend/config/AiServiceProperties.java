package com.intelliassist.backend.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for the Python AI service.
 *
 * <p>Validated at startup, so a deployment missing the API key fails
 * immediately with a clear message rather than serving traffic that returns 401
 * from the AI service on the first document upload.
 *
 * <p>{@code apiKey} is a shared secret proving this backend is the caller. It
 * carries no user identity and is not authentication in the usual sense. It
 * must never be logged, returned through a public API, stored in the database,
 * or sent to a browser — hence {@link #toString()} below.
 *
 * @param baseUrl        root URL of the AI service, no trailing slash
 * @param apiKey         value sent as the {@code X-API-Key} header
 * @param connectTimeout TCP connect budget
 * @param readTimeout    response budget for reads and uploads
 * @param queryTimeout   response budget for RAG generation, which is slower
 * @param streamFirstEventTimeout budget for the first SSE event, which waits on
 *     retrieval and reranking before anything is emitted
 * @param streamIdleTimeout maximum gap between SSE events once flowing; a
 *     stalled stream is detected without capping the total duration, because a
 *     long answer is legitimately long
 */
@Validated
@ConfigurationProperties(prefix = "intelliassist.ai-service")
public record AiServiceProperties(
        @NotBlank(message = "AI_SERVICE_BASE_URL must be set") String baseUrl,
        @NotBlank(message = "AI_SERVICE_API_KEY must be set") String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration queryTimeout,
        Duration streamFirstEventTimeout,
        Duration streamIdleTimeout,
        Integer historyTurns) {

    public AiServiceProperties {
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(10);
        queryTimeout = queryTimeout != null ? queryTimeout : Duration.ofSeconds(70);

        // Streaming must not reuse the request/response budget. A total-duration
        // timeout would kill a long but perfectly healthy answer, so the two
        // limits below bound *latency*, not length: how long we wait for the
        // stream to start, and how long a silence is tolerated once it has.
        streamFirstEventTimeout =
                streamFirstEventTimeout != null ? streamFirstEventTimeout : Duration.ofSeconds(75);
        streamIdleTimeout = streamIdleTimeout != null ? streamIdleTimeout : Duration.ofSeconds(30);

        // How many prior messages travel with a question so the AI service can
        // resolve a follow-up. Six is about three exchanges, which covers the
        // references people actually make without paying to resend a long
        // conversation on every turn. Zero disables it from this side.
        historyTurns = historyTurns != null ? Math.max(0, historyTurns) : 6;
    }

    /**
     * Redacts the API key.
     *
     * <p>Spring prints configuration properties in several places — startup
     * failure reports and actuator among them — and the default record
     * {@code toString} would print the secret in full.
     */
    @Override
    public String toString() {
        return ("AiServiceProperties[baseUrl=%s, apiKey=***, connectTimeout=%s, readTimeout=%s, "
                        + "queryTimeout=%s, streamFirstEventTimeout=%s, streamIdleTimeout=%s, "
                        + "historyTurns=%s]")
                .formatted(
                        baseUrl,
                        connectTimeout,
                        readTimeout,
                        queryTimeout,
                        streamFirstEventTimeout,
                        streamIdleTimeout,
                        historyTurns);
    }
}
