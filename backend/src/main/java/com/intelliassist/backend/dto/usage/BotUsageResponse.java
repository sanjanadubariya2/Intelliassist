package com.intelliassist.backend.dto.usage;

import java.time.LocalDate;
import java.util.List;

/**
 * A bot's usage over a date range.
 *
 * <p>Days with no activity are absent rather than present as zeros. Filling the
 * gaps would triple the payload of a quiet month to say nothing, and a chart
 * can interpolate a missing day far more cheaply than we can transmit it.
 *
 * <p>Totals are included so the common case — "how busy was this bot" — needs
 * no client-side arithmetic.
 *
 * @param totalAnswers assistant replies over the range. Half of
 *     {@code totalMessages}, which counts both sides of an exchange.
 * @param groundedAnswers answers backed by at least one document
 * @param ungroundedAnswers total answers where retrieval found nothing
 * @param averageResponseMs mean assistant latency over the range, or null
 *     when the bot answered nothing. Null is not zero, and a client must
 *     not render it as though the bot replied instantly.
 */
public record BotUsageResponse(
        java.util.UUID botId,
        LocalDate from,
        LocalDate to,
        List<UsageDayResponse> days,
        int totalConversations,
        int totalMessages,
        int totalAnswers,
        int groundedAnswers,
        int ungroundedAnswers,
        long totalTokens,
        Double averageResponseMs) {

    /**
     * Share of answers that could not be grounded.
     *
     * <p>Surfaced as a ratio because the raw count means nothing without
     * volume: five unanswered questions out of ten is a problem, out of ten
     * thousand is noise.
     */
    public double ungroundedRate() {
        return totalAnswers == 0 ? 0.0 : (double) ungroundedAnswers / totalAnswers;
    }
}
