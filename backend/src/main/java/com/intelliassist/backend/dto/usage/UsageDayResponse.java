package com.intelliassist.backend.dto.usage;

import com.intelliassist.backend.entity.UsageRecord;
import java.time.LocalDate;

/**
 * One day of a bot's activity.
 *
 * @param answers assistant replies. Half of {@code messages}, which counts
 *     both sides of an exchange -- stated outright so a client is never left
 *     dividing by two on a convention it cannot see.
 * @param groundedAnswers answers backed by at least one document
 * @param ungroundedAnswers answers where retrieval found nothing. The most
 *     actionable number here: a rising count means the corpus has a gap, and
 *     the fix is uploading a document rather than tuning anything.
 * @param tokens a floor, not an exact figure — streamed answers do not report
 *     usage and so contribute zero
 */
public record UsageDayResponse(
        LocalDate date,
        int conversations,
        int messages,
        int answers,
        int groundedAnswers,
        int ungroundedAnswers,
        long tokens) {

    public static UsageDayResponse from(UsageRecord record) {
        // messages counts the visitor's turn and the bot's, so the number of
        // answers is half of it. See UsageService.recordExchange, which is the
        // only writer and always increments by two.
        int answers = record.getMessageCount() / 2;

        return new UsageDayResponse(
                record.getUsageDate(),
                record.getConversationCount(),
                record.getMessageCount(),
                answers,
                Math.max(0, answers - record.getUngroundedCount()),
                record.getUngroundedCount(),
                record.getTokenCount());
    }
}
