package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A bot's corpus, as this application knows it.
 *
 * <p>Deliberately thin. It holds ownership and a reference — the actual
 * knowledge base, with its chunks, embeddings and vector index, lives entirely
 * in the AI service. Duplicating any of that here would create a second source
 * of truth that drifts the first time an ingestion partially fails.
 *
 * <p>One per bot, enforced by a unique constraint. The document endpoints are
 * addressed by bot and name no knowledge base, so a second one would make
 * "which corpus does this upload go to" unanswerable.
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false, updatable = false, unique = true)
    private Bot bot;

    @Column(nullable = false, length = 200)
    private String name;

    /** The AI service's own id. The join between the two systems. */
    @Column(name = "ai_knowledge_base_id", nullable = false, unique = true, updatable = false)
    private UUID aiKnowledgeBaseId;

    /**
     * What we sent as {@code external_ref}.
     *
     * <p>Kept so the link can be rebuilt by listing the AI service's knowledge
     * bases if this row is ever lost — otherwise a corpus would be stranded,
     * costing storage with nothing able to query or delete it.
     */
    @Column(name = "external_ref", nullable = false, length = 255, updatable = false)
    private String externalRef;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected KnowledgeBase() {
        // Required by JPA.
    }

    public KnowledgeBase(Bot bot, String name, UUID aiKnowledgeBaseId, String externalRef) {
        this.bot = bot;
        this.name = name;
        this.aiKnowledgeBaseId = aiKnowledgeBaseId;
        this.externalRef = externalRef;
    }

    /** The external_ref format sent to the AI service. One per bot. */
    public static String externalRefFor(UUID botId) {
        return "bot-" + botId;
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getAiKnowledgeBaseId() {
        return aiKnowledgeBaseId;
    }

    public String getExternalRef() {
        return externalRef;
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
        if (!(other instanceof KnowledgeBase that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    @Override
    public String toString() {
        return "KnowledgeBase[id=%s, aiKnowledgeBaseId=%s]".formatted(id, aiKnowledgeBaseId);
    }
}
