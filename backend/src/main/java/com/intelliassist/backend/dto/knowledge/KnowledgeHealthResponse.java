package com.intelliassist.backend.dto.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A health check of one bot's knowledge base.
 *
 * <p>Everything here is computed from document metadata and conversation
 * history, never from the documents' text, which lives only in the AI service.
 * That bounds what it can claim: "conflicting" means several versions of what
 * looks like the same document, not two sentences that contradict each other.
 * The screen says so.
 *
 * @param score 0 to 100; 0 for an empty knowledge base
 */
public record KnowledgeHealthResponse(
        int score,
        int documentCount,
        int indexedCount,
        int totalChunks,
        List<Issue> issues,
        List<SuggestedFaq> suggestedFaqs,
        Instant checkedAt) {

    /** What kind of problem, ordered roughly by how much it hurts answers. */
    public enum IssueType {
        EMPTY_KNOWLEDGE_BASE,
        FAILED,
        STUCK,
        NO_CONTENT,
        CONFLICTING_VERSIONS,
        DUPLICATE,
        OUTDATED
    }

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * One finding.
     *
     * @param documentIds the documents involved; empty for whole-base findings
     * @param recommendation what to do about it, in a sentence
     */
    public record Issue(
            IssueType type,
            Severity severity,
            String title,
            String detail,
            String recommendation,
            List<UUID> documentIds,
            List<String> filenames) {}

    /**
     * A question visitors asked that no document answered.
     *
     * <p>Each one is evidence of a gap rather than a guess at one: someone asked
     * it, and retrieval found nothing.
     */
    public record SuggestedFaq(String question, int timesAsked, Instant lastAskedAt) {}
}
