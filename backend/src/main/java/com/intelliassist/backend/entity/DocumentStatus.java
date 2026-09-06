package com.intelliassist.backend.entity;

import java.util.Set;

/**
 * Ingestion lifecycle, mirroring the AI service's own states.
 *
 * <p>Mirrored rather than simplified to two states, because "it is embedding"
 * and "it has not started" are genuinely different things to a customer
 * watching a 200-page PDF, and the AI service already reports the difference.
 *
 * <p>Mapping from the AI service's lowercase strings happens in
 * {@link #fromAiStatus}; anything unrecognised stays PENDING rather than
 * guessing, so a new upstream state cannot silently look like success.
 */
public enum DocumentStatus {

    /** Accepted by us, and either not yet handed off or not yet started. */
    PENDING,

    EXTRACTING,
    CHUNKING,
    EMBEDDING,

    /** Terminal, and the only state in which a document is queryable. */
    INDEXED,

    /** Terminal. Always accompanied by an error message. */
    FAILED;

    private static final Set<DocumentStatus> TERMINAL = Set.of(INDEXED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True while the AI service may still change this document's state. */
    public boolean isInProgress() {
        return !isTerminal();
    }

    public static DocumentStatus fromAiStatus(String aiStatus) {
        if (aiStatus == null) {
            return PENDING;
        }
        return switch (aiStatus.toLowerCase(java.util.Locale.ROOT)) {
            case "extracting" -> EXTRACTING;
            case "chunking" -> CHUNKING;
            case "embedding" -> EMBEDDING;
            case "indexed" -> INDEXED;
            case "failed" -> FAILED;
            // Covers "pending" and any state added upstream later. Treating an
            // unknown value as INDEXED would be far worse than treating it as
            // not-yet-ready.
            default -> PENDING;
        };
    }
}
