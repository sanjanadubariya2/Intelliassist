package com.intelliassist.backend.dto.insights;

import java.time.Instant;

/**
 * Questions visitors asked that read the same once case, punctuation and
 * spacing are set aside.
 *
 * @param question the most recent wording, as the visitor typed it
 * @param count how many times it was asked
 * @param unansweredCount how many of those got no grounded answer
 * @param lastAskedAt when it was most recently asked
 */
public record QuestionClusterResponse(
        String question, int count, int unansweredCount, Instant lastAskedAt) {}
