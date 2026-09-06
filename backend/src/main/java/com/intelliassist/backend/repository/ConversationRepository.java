package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.Conversation;
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

}
