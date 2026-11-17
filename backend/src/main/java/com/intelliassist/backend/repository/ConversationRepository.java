package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.Conversation;
import com.intelliassist.backend.entity.ConversationResolution;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * The safe lookup: a conversation exists only if the caller's organization
     * owns the bot it belongs to.
     *
     * <p>The bot is fetched in the same statement because the caller
     * immediately needs it to resolve the knowledge base - without the fetch
     * this is two queries on the hot chat path.
     */
    @Query("""
            SELECT c FROM Conversation c
            JOIN FETCH c.bot b
            WHERE c.id = :conversationId AND b.organization.id = :organizationId
            """)
    Optional<Conversation> findByIdAndOrganizationId(
            @Param("conversationId") UUID conversationId,
            @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT c FROM Conversation c
            JOIN c.bot b
            WHERE b.id = :botId AND b.organization.id = :organizationId
            """)
    Page<Conversation> findAllByBotIdAndOrganizationId(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            Pageable pageable);

    // ------------------------------------------------------------ handoff --
    //
    // Every inbox query is org-wide rather than per bot: a support team works
    // one queue, not one queue per assistant. The bot and assignee are fetched
    // in the same statement because every row shows both.

    /** Conversations waiting on a person, optionally only one teammate's. */
    @Query(
            value = """
                    SELECT c FROM Conversation c
                    JOIN FETCH c.bot b
                    LEFT JOIN FETCH c.assignee
                    WHERE b.organization.id = :organizationId
                      AND c.resolution = :resolution
                    ORDER BY c.escalatedAt DESC NULLS LAST
                    """,
            countQuery = """
                    SELECT COUNT(c) FROM Conversation c
                    WHERE c.bot.organization.id = :organizationId
                      AND c.resolution = :resolution
                    """)
    Page<Conversation> findInboxByResolution(
            @Param("organizationId") UUID organizationId,
            @Param("resolution") ConversationResolution resolution,
            Pageable pageable);

    @Query(
            value = """
                    SELECT c FROM Conversation c
                    JOIN FETCH c.bot b
                    LEFT JOIN FETCH c.assignee a
                    WHERE b.organization.id = :organizationId
                      AND c.resolution = com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                      AND a.id = :assigneeId
                    ORDER BY c.escalatedAt DESC NULLS LAST
                    """,
            countQuery = """
                    SELECT COUNT(c) FROM Conversation c
                    WHERE c.bot.organization.id = :organizationId
                      AND c.resolution = com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                      AND c.assignee.id = :assigneeId
                    """)
    Page<Conversation> findInboxAssignedTo(
            @Param("organizationId") UUID organizationId,
            @Param("assigneeId") UUID assigneeId,
            Pageable pageable);

    @Query(
            value = """
                    SELECT c FROM Conversation c
                    JOIN FETCH c.bot b
                    WHERE b.organization.id = :organizationId
                      AND c.resolution = com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                      AND c.assignee IS NULL
                    ORDER BY c.escalatedAt DESC NULLS LAST
                    """,
            countQuery = """
                    SELECT COUNT(c) FROM Conversation c
                    WHERE c.bot.organization.id = :organizationId
                      AND c.resolution = com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                      AND c.assignee IS NULL
                    """)
    Page<Conversation> findInboxUnassigned(
            @Param("organizationId") UUID organizationId, Pageable pageable);

    /** Handoffs that have since been closed out: escalated once, no longer. */
    @Query(
            value = """
                    SELECT c FROM Conversation c
                    JOIN FETCH c.bot b
                    LEFT JOIN FETCH c.assignee
                    WHERE b.organization.id = :organizationId
                      AND c.escalatedAt IS NOT NULL
                      AND c.resolution <> com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                    ORDER BY c.resolvedAt DESC NULLS LAST
                    """,
            countQuery = """
                    SELECT COUNT(c) FROM Conversation c
                    WHERE c.bot.organization.id = :organizationId
                      AND c.escalatedAt IS NOT NULL
                      AND c.resolution <> com.intelliassist.backend.entity.ConversationResolution.ESCALATED
                    """)
    Page<Conversation> findInboxClosedOut(
            @Param("organizationId") UUID organizationId, Pageable pageable);

    /**
     * Escalation and resolution moments of handoffs resolved since a point.
     *
     * <p>Two instants per row rather than entities: the caller only measures the
     * gap between them, and loading conversations to do that would be waste.
     */
    @Query("""
            SELECT c.escalatedAt, c.resolvedAt FROM Conversation c
            WHERE c.bot.organization.id = :organizationId
              AND c.escalatedAt IS NOT NULL
              AND c.resolvedAt IS NOT NULL
              AND c.resolution = com.intelliassist.backend.entity.ConversationResolution.RESOLVED
              AND c.resolvedAt >= :since
            """)
    List<Object[]> findHandoffDurationsSince(
            @Param("organizationId") UUID organizationId, @Param("since") Instant since);

    // ----------------------------------------------------------- insights --

    /** When each conversation in a window started, and how it turned out. */
    @Query("""
            SELECT c.startedAt, c.resolution FROM Conversation c
            WHERE c.bot.id = :botId
              AND c.bot.organization.id = :organizationId
              AND c.startedAt >= :from
              AND c.startedAt < :to
            """)
    List<Object[]> findOutcomesStartedBetween(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
