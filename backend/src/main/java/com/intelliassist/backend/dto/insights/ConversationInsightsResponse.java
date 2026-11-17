package com.intelliassist.backend.dto.insights;

import java.time.LocalDate;
import java.util.List;

/**
 * What visitors ask a bot, what it cannot answer, and how conversations end.
 *
 * <p>Rates are null rather than zero when there is nothing to divide by: a bot
 * nobody has talked to has no answer rate, which is different from one that
 * answers nothing.
 *
 * @param satisfactionRate resolved / (resolved + escalated). A proxy: there is
 *     no visitor rating, so this measures how often a conversation that reached
 *     an outcome reached a good one.
 * @param truncated true when the window held more messages than are analysed,
 *     in which case the question lists describe the most recent ones only
 */
public record ConversationInsightsResponse(
        LocalDate from,
        LocalDate to,
        int totalQuestions,
        int answeredQuestions,
        int unansweredQuestions,
        Double answerRate,
        int totalConversations,
        int resolvedConversations,
        int escalatedConversations,
        int openConversations,
        Double satisfactionRate,
        List<QuestionClusterResponse> topQuestions,
        List<QuestionClusterResponse> unansweredTopics,
        List<ResolutionDayResponse> trend,
        boolean truncated) {}
