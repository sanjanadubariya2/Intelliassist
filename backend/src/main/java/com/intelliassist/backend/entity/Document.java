package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Application-level metadata for an uploaded file.
 *
 * <p>The bytes are not stored here. This backend forwards them to the AI
 * service, which extracts, chunks and embeds them; keeping a second copy would
 * add storage cost and a compliance surface for no capability we need.
 *
 * <p>Status is a mirror, not a source of truth. The AI service owns ingestion
 * progress, and {@link #applyAiStatus} is the single place that copy is
 * refreshed.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_base_id", nullable = false, updatable = false)
    private KnowledgeBase knowledgeBase;

    /** Sanitised name, safe to store and display. */
    @Column(nullable = false, length = 255)
    private String filename;

    /** Exactly what the user uploaded, shown back to them. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * The AI service's document id.
     *
     * <p>Null only between our row being written and the upload being accepted.
     * A row that stays null means the handoff failed, and the status will be
     * FAILED with a reason.
     */
    @Column(name = "ai_document_id", unique = true)
    private UUID aiDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "page_count")
    private Integer pageCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Document() {
        // Required by JPA.
    }

    public Document(
            KnowledgeBase knowledgeBase,
            String filename,
            String originalFilename,
            String contentType,
            long sizeBytes) {
        this.knowledgeBase = knowledgeBase;
        this.filename = filename;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = DocumentStatus.PENDING;
    }

    /** Records a successful handoff to the AI service. */
    public void markAccepted(UUID aiDocumentId, DocumentStatus aiStatus) {
        this.aiDocumentId = aiDocumentId;
        this.status = aiStatus;
        this.errorMessage = null;
    }

    /**
     * Refreshes the local mirror of ingestion progress.
     *
     * <p>Counts are only copied when present: the AI service reports them once
     * ingestion reaches the relevant stage, and overwriting a real count with
     * null on a later poll would lose information.
     */
    public void applyAiStatus(
            DocumentStatus aiStatus, String errorMessage, Integer chunkCount, Integer pageCount) {
        this.status = aiStatus;
        this.errorMessage = errorMessage;
        if (chunkCount != null) {
            this.chunkCount = chunkCount;
        }
        if (pageCount != null) {
            this.pageCount = pageCount;
        }
    }

    /** Records a failure on our side of the boundary. */
    public void markFailed(String reason) {
        this.status = DocumentStatus.FAILED;
        this.errorMessage = reason;
    }

    public UUID getId() {
        return id;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public String getFilename() {
        return filename;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public UUID getAiDocumentId() {
        return aiDocumentId;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Document that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** Omits the filename, which can carry customer information. */
    @Override
    public String toString() {
        return "Document[id=%s, status=%s]".formatted(id, status);
    }
}
