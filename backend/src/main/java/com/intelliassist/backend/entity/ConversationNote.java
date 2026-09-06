package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An operator's private note on a conversation.
 *
 * <p>Never returned by the public API and never sent to the AI service. These
 * are staff commentary, frequently <em>about</em> the visitor, and leaking one
 * into an answer would be considerably worse than a wrong answer.
 */
@Entity
@Table(name = "conversation_notes")
public class ConversationNote {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    /**
     * The author, or null once their account is deleted.
     *
     * <p>The note survives the user rather than cascading away with them: it is
     * a record of what someone knew at the time, and deleting an employee
     * should not erase the history of a customer's case.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    /**
     * Denormalised deliberately.
     *
     * <p>Copied at write time so a note still says who wrote it after that
     * account is gone — which is exactly when someone reads it and asks.
     */
    @Column(name = "author_name", nullable = false, length = 255)
    private String authorName;

    @Column(nullable = false, length = 2000)
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationNote() {
        // JPA.
    }

    public ConversationNote(Conversation conversation, User author, String body) {
        this.conversation = conversation;
        this.author = author;
        this.authorName = author.getName();
        this.body = body;
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public UUID getAuthorId() {
        return author == null ? null : author.getId();
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
