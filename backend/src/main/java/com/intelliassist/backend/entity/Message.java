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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One turn in a conversation.
 *
 * <p>An assistant message carries the answer plus everything needed to explain
 * it: its citations, whether it was grounded, which model produced it, and what
 * it cost. Keeping that with the message rather than recomputing it means a
 * transcript from six months ago still shows exactly what the user was told and
 * on what basis.
 */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /**
     * Sources, stored as JSONB.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is Hibernate 6's native mapping —
     * no third-party type library needed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<MessageCitation> citations = List.of();

    /**
     * Whether retrieval found anything relevant.
     *
     * <p>Null for user messages. False means the AI service declined without
     * calling a model — worth recording, because "we could not answer" is the
     * metric that reveals a gap in the corpus.
     */
    @Column
    private Boolean grounded;

    @Column(length = 100)
    private String model;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
        // Required by JPA.
    }

    private Message(Conversation conversation, MessageRole role, String content) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
    }

    public static Message user(Conversation conversation, String content) {
        return new Message(conversation, MessageRole.USER, content);
    }

    public static Message assistant(
            Conversation conversation,
            String content,
            List<MessageCitation> citations,
            boolean grounded,
            String model,
            int totalTokens,
            int latencyMs) {

        Message message = new Message(conversation, MessageRole.ASSISTANT, content);
        message.citations = citations == null ? List.of() : List.copyOf(citations);
        message.grounded = grounded;
        message.model = model;
        message.totalTokens = totalTokens;
        message.latencyMs = latencyMs;
        return message;
    }

    public UUID getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<MessageCitation> getCitations() {
        return citations == null ? List.of() : citations;
    }

    public Boolean getGrounded() {
        return grounded;
    }

    public String getModel() {
        return model;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Message that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** Never includes the content, which is customer conversation data. */
    @Override
    public String toString() {
        return "Message[id=%s, role=%s]".formatted(id, role);
    }
}
