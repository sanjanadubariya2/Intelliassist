package com.intelliassist.backend.integration.ai;

import com.intelliassist.backend.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A failure talking to the AI service.
 *
 * <p>Two responsibilities, and the second is the important one:
 *
 * <ol>
 *   <li>Translate the AI service's status into ours. Its {@code 502} means
 *       "Gemini failed" — for our caller that is a transient {@code 503},
 *       because retrying later may well work.
 *   <li><strong>Never let its message reach our client.</strong> The upstream
 *       message names models, providers and internal state. That belongs in our
 *       logs, joined by the request id; the client gets wording we chose.
 * </ol>
 */
public class AiServiceException extends ApiException {

    private final String upstreamCode;
    private final String upstreamRequestId;

    private AiServiceException(
            HttpStatus status,
            String code,
            String clientMessage,
            String upstreamCode,
            String upstreamRequestId) {
        super(status, code, clientMessage);
        this.upstreamCode = upstreamCode;
        this.upstreamRequestId = upstreamRequestId;
    }

    /** The AI service is unreachable, or timed out. */
    public static AiServiceException unavailable(String upstreamRequestId) {
        return new AiServiceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_SERVICE_UNAVAILABLE",
                "The AI service is temporarily unavailable. Please try again.",
                "unreachable",
                upstreamRequestId);
    }

    public static AiServiceException timeout() {
        return new AiServiceException(
                HttpStatus.GATEWAY_TIMEOUT,
                "AI_SERVICE_TIMEOUT",
                "The AI service did not respond in time. Please try again.",
                "timeout",
                null);
    }

    /**
     * The AI service rejected our credentials.
     *
     * <p>Deliberately surfaced as a {@code 500}: this is our misconfiguration,
     * not the caller's fault, and it must never be reported as though the
     * end user's own authentication failed.
     */
    public static AiServiceException misconfigured(String upstreamRequestId) {
        return new AiServiceException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "AI_SERVICE_MISCONFIGURED",
                "An internal error occurred.",
                "unauthorized",
                upstreamRequestId);
    }

    public static AiServiceException notFound(String upstreamRequestId) {
        return new AiServiceException(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "The requested resource was not found in the AI service.",
                "not_found",
                upstreamRequestId);
    }

    public static AiServiceException conflict(String message, String upstreamRequestId) {
        return new AiServiceException(
                HttpStatus.CONFLICT, "CONFLICT", message, "conflict", upstreamRequestId);
    }

    public static AiServiceException rejected(
            HttpStatus status, String code, String clientMessage, String upstreamRequestId) {
        return new AiServiceException(status, code, clientMessage, code, upstreamRequestId);
    }

    /** Generation or embedding failed upstream. Transient from our side. */
    public static AiServiceException upstreamFailure(String upstreamCode, String upstreamRequestId) {
        return new AiServiceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_SERVICE_ERROR",
                "The AI service could not complete the request. Please try again.",
                upstreamCode,
                upstreamRequestId);
    }

    /** For logs only. Never returned to a client. */
    public String getUpstreamCode() {
        return upstreamCode;
    }

    /** The AI service's request id, for joining its logs to ours. */
    public String getUpstreamRequestId() {
        return upstreamRequestId;
    }
}
