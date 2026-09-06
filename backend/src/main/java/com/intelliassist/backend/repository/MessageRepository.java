package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.Message;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * A transcript, oldest first.
     *
     * <p>Callers reach this only after the conversation itself has been
     * authorised, so no organization predicate is needed here - the ownership
     * check has already happened one level up.
     */
    List<Message> findAllByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /**
     * The most recent messages, newest first.
     *
     * <p>Ordered descending and limited by the database so a long conversation
     * never loads in full just to read its tail. The caller reverses the small
     * result to get chronological order.
     */
    List<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    Page<Message> findAllByConversationIdOrderByCreatedAtAsc(
            UUID conversationId, Pageable pageable);

    long countByConversationId(UUID conversationId);

    /**
     * Mean assistant response time for a bot over a period, in milliseconds.
     *
     * <p>Computed in the database. Loading every message to average a column in
     * Java would pull an entire conversation history across the wire to produce
     * one number, and would get slower every day the product is used.
     *
     * <p>Null when the bot has answered nothing in the window — which is not
     * the same as zero, and the caller must not treat it as such.
     */
    @Query("""
            SELECT AVG(m.latencyMs) FROM Message m
            WHERE m.conversation.bot.id = :botId
              AND m.conversation.bot.organization.id = :organizationId
              AND m.role = com.intelliassist.backend.entity.MessageRole.ASSISTANT
              AND m.latencyMs IS NOT NULL
              AND m.createdAt >= :from
              AND m.createdAt < :to
            """)
    Double averageLatencyMs(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
