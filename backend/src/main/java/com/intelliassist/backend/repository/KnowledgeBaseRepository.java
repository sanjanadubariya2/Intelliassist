package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.KnowledgeBase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    /**
     * Loads a bot's knowledge base, scoped to the owning organization.
     *
     * <p>The organization predicate reaches through the bot. Without it a
     * caller could name any bot id and get its corpus - the join is what makes
     * that impossible rather than merely guarded.
     */
    @Query("""
            SELECT kb FROM KnowledgeBase kb
            JOIN FETCH kb.bot b
            WHERE b.id = :botId AND b.organization.id = :organizationId
            """)
    Optional<KnowledgeBase> findByBotIdAndOrganizationId(
            @Param("botId") UUID botId, @Param("organizationId") UUID organizationId);

    boolean existsByBotId(UUID botId);
}
