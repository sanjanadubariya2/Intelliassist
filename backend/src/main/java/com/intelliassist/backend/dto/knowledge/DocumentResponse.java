package com.intelliassist.backend.dto.knowledge;

import com.intelliassist.backend.entity.Document;
import com.intelliassist.backend.entity.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Document metadata and ingestion progress.
 *
 * <p>{@code aiDocumentId} is not exposed: it is an internal reference, and the
 * dashboard has our own id for everything it needs to do.
 *
 * @param queryable convenience for the UI - true only at INDEXED, which is the
 *                  distinction that actually matters to a customer waiting for
 *                  an upload to become answerable
 */
public record DocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String filename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        boolean queryable,
        String errorMessage,
        Integer chunkCount,
        Integer pageCount,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getKnowledgeBase().getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getStatus(),
                document.getStatus() == DocumentStatus.INDEXED,
                document.getErrorMessage(),
                document.getChunkCount(),
                document.getPageCount(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
