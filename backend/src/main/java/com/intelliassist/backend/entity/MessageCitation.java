package com.intelliassist.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A source an assistant message drew on, as stored.
 *
 * <p>An embedded value, not an entity. A citation belongs to exactly one
 * message, is always read with it, and is never queried on its own — the
 * textbook case for storing it inside the row rather than normalising it into a
 * child table that would add a join to every transcript read.
 *
 * @param marker     matches an inline {@code [S1]} in the answer text
 * @param documentId the AI service's document id, which maps to our
 *                   {@code Document.aiDocumentId}
 * @param filename   what the user recognises the source as
 * @param pageNumber present for PDFs, null for DOCX and TXT
 * @param snippet    the supporting passage, already truncated for display
 * @param score      relevance from the final ranking stage
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageCitation(
        int marker,
        UUID documentId,
        String filename,
        Integer pageNumber,
        String snippet,
        double score) {}
