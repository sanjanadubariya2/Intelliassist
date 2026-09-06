package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One bot's activity on one day.
 *
 * <p>A rollup rather than an event log. Storing a row per message and
 * aggregating on read would cost far more and answer nothing extra — the
 * messages table already holds the detail for anyone who needs it.
 *
 * <p>Counters are only ever incremented, never recomputed, so a correction
 * means a deliberate backfill rather than an accidental drift.
 */
@Entity
@Table(
        name = "usage_records",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_usage_bot_date",
                        columnNames = {"bot_id", "usage_date"}))
public class UsageRecord {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false, updatable = false)
    private Bot bot;

    /** UTC day bucket, so a report does not shift with the reader's timezone. */
    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @Column(name = "conversation_count", nullable = false)
    private int conversationCount;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "ungrounded_count", nullable = false)
    private int ungroundedCount;

    @Column(name = "token_count", nullable = false)
    private long tokenCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected UsageRecord() {
        // Required by JPA.
    }

    public UsageRecord(Bot bot, LocalDate usageDate) {
        this.bot = bot;
        this.usageDate = usageDate;
    }

    public void recordConversationStarted() {
        this.conversationCount++;
    }

    /**
     * Records one answered exchange.
     *
     * @param ungrounded whether retrieval found nothing, which is the number
     *     that reveals a gap in the corpus
     */
    public void recordExchange(boolean ungrounded, int tokens) {
        // Two messages per exchange: the question and the answer, matching what
        // a transcript actually contains.
        this.messageCount += 2;
        if (ungrounded) {
            this.ungroundedCount++;
        }
        this.tokenCount += Math.max(tokens, 0);
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public int getConversationCount() {
        return conversationCount;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public int getUngroundedCount() {
        return ungroundedCount;
    }

    public long getTokenCount() {
        return tokenCount;
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
        if (!(other instanceof UsageRecord that)) {
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
        return "UsageRecord[bot=%s, date=%s, messages=%d]"
                .formatted(bot == null ? null : bot.getId(), usageDate, messageCount);
    }
}
