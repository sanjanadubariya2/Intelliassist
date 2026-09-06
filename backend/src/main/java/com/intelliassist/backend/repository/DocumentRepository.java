package com.intelliassist.backend.repository;

import com.intelliassist.backend.entity.Document;
import com.intelliassist.backend.entity.DocumentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * The safe lookup: a document exists only if the caller's organization owns
     * the bot that owns its knowledge base.
     *
     * <p>Three hops - document, knowledge base, bot, organization - collapsed
     * into one query. Doing it in steps would mean three chances to forget the
     * final check.
     */
    @Query("""
            SELECT d FROM Document d
            JOIN FETCH d.knowledgeBase kb
            JOIN FETCH kb.bot b
            WHERE d.id = :documentId AND b.organization.id = :organizationId
            """)
    Optional<Document> findByIdAndOrganizationId(
            @Param("documentId") UUID documentId,
            @Param("organizationId") UUID organizationId);

    @Query("""
            SELECT d FROM Document d
            JOIN d.knowledgeBase kb
            JOIN kb.bot b
            WHERE b.id = :botId AND b.organization.id = :organizationId
            """)
    Page<Document> findAllByBotIdAndOrganizationId(
            @Param("botId") UUID botId,
            @Param("organizationId") UUID organizationId,
            Pageable pageable);

    /**
     * Documents whose ingestion may still be running.
     *
     * <p>Backs status reconciliation: only these need to be re-checked against
     * the AI service, so polling cost stays proportional to work in flight
     * rather than to corpus size.
     */
    @Query("""
            SELECT d FROM Document d
            WHERE d.knowledgeBase.id = :knowledgeBaseId
              AND d.status NOT IN :terminal
              AND d.aiDocumentId IS NOT NULL
            """)
    List<Document> findUnfinished(
            @Param("knowledgeBaseId") UUID knowledgeBaseId,
            @Param("terminal") List<DocumentStatus> terminal);

    long countByKnowledgeBaseId(UUID knowledgeBaseId);
}
