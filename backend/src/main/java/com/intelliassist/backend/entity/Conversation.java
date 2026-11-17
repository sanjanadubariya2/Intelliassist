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
 * A thread of messages with one bot.
 *
 * <p>All conversation state lives here, because the AI service has none: it
 * answers a single question against a knowledge base and remembers nothing
 * between calls. Threading, ordering and history are entirely this
 * application's responsibility, which is also why they can be queried,
 * exported and analysed with ordinary SQL.
 *
 * <p>No {@code @OneToMany} to messages. A conversation can run to hundreds of
 * turns, and mapping the collection invites loading all of them to answer a
 * question about one.
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false, updatable = false)
    private Bot bot;

    /**
     * Opaque, caller-supplied identity for the person talking.
     *
     * <p>Deliberately not a user id: most conversations come from anonymous
     * website visitors who have no account. Null for dashboard testing.
     */
    @Column(name = "visitor_id", length = 128, updatable = false)
    private String visitorId;

    @Column(length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status;

    /**
     * Whether the visitor got what they came for.
     *
     * <p>Separate from {@link #status}, which is only whether the conversation
     * is still open. A conversation can be CLOSED and ESCALATED at once, and
     * one column could not say so.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationResolution resolution = ConversationResolution.UNRESOLVED;

    /**
     * The teammate handling it once a person is needed.
     *
     * <p>Null means it sits in the shared queue. Lazy, and only ever read inside
     * a transaction, because almost every conversation never has one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** First escalation. Never cleared, so a resolved handoff stays countable. */
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** Most recent resolution. Cleared when the conversation is reopened. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    /** Denormalised so the conversation list sorts by recency without a join. */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Conversation() {
        // Required by JPA.
    }

    public Conversation(Bot bot, String visitorId) {
        this.bot = bot;
        this.visitorId = visitorId;
        this.status = ConversationStatus.ACTIVE;
        this.startedAt = Instant.now();
        this.messageCount = 0;
    }

    /**
     * Records that a message was added.
     *
     * <p>Called once per exchange, counting both the question and the answer,
     * so the counter matches what a transcript actually contains.
     */
    public void recordMessages(int added, Instant at) {
        this.messageCount += added;
        this.lastMessageAt = at;
    }

    /** The first question becomes the title, so a list is readable at a glance. */
    public void titleFromFirstMessage(String content) {
        if (title != null || content == null || content.isBlank()) {
            return;
        }
        String trimmed = content.strip();
        this.title = trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    public void close() {
        this.status = ConversationStatus.CLOSED;
    }

    public boolean isActive() {
        return status == ConversationStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public String getVisitorId() {
        return visitorId;
    }

    public String getTitle() {
        return title;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public int getMessageCount() {
        return messageCount;
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
        if (!(other instanceof Conversation that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** Omits the title, which is a visitor's own words. */
    @Override
    public String toString() {
        return "Conversation[id=%s, status=%s, messages=%d]".formatted(id, status, messageCount);
    }

    public ConversationResolution getResolution() {
        return resolution;
    }

    /**
     * Records how the conversation turned out.
     *
     * <p>Marking it resolved or escalated does not close it: a visitor may
     * well keep typing after an operator has judged the outcome.
     */
    public void resolveAs(ConversationResolution resolution) {
        resolveAs(resolution, Instant.now());
    }

    /**
     * As {@link #resolveAs(ConversationResolution)}, with the moment supplied.
     *
     * <p>Escalation is stamped once and kept: a conversation that was handed to
     * a person and then resolved is still a handoff, and the inbox measures how
     * long it took. Resolution is stamped each time and cleared on reopen.
     */
    public void resolveAs(ConversationResolution resolution, Instant at) {
        this.resolution = resolution;
        switch (resolution) {
            case ESCALATED -> {
                if (escalatedAt == null) {
                    escalatedAt = at;
                }
                resolvedAt = null;
            }
            case RESOLVED -> resolvedAt = at;
            case UNRESOLVED -> resolvedAt = null;
        }
    }

    public User getAssignee() {
        return assignee;
    }

    /** Null returns it to the shared queue. */
    public void assignTo(User assignee) {
        this.assignee = assignee;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
