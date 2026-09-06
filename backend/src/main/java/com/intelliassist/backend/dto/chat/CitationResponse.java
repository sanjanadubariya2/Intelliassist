package com.intelliassist.backend.dto.chat;

import com.intelliassist.backend.entity.MessageCitation;
import java.util.UUID;

/**
 * A source behind an answer.
 *
 * <p>{@code marker} matches an inline {@code [S1]} in the answer text, which is
 * what lets a UI turn a sentence into a clickable source rather than showing a
 * detached list of references.
 *
 * <p>{@code documentId} here is the <em>AI service's</em> id, kept because it
 * is what the answer was actually grounded in. Resolving it to one of our own
 * documents is a lookup the dashboard can do when it needs a download link.
 */
public record CitationResponse(
        int marker,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String snippet,
        double score) {

    public static CitationResponse from(MessageCitation citation) {
        return new CitationResponse(
                citation.marker(),
                citation.documentId(),
                citation.filename(),
                citation.pageNumber(),
                citation.snippet(),
                citation.score());
    }
}
