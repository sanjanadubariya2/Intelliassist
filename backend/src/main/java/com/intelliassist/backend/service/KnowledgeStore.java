package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.dto.knowledge.DocumentResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeBaseResponse;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.Document;
import com.intelliassist.backend.entity.DocumentStatus;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.DocumentRepository;
import com.intelliassist.backend.repository.KnowledgeBaseRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of knowledge management.
 *
 * <p>A separate bean on purpose. {@link KnowledgeService} orchestrates HTTP
 * calls to the AI service and must not hold a database transaction open across
 * them — but {@code @Transactional} on a private method of that same class
 * would do nothing at all, because Spring's proxy cannot intercept a call made
 * through {@code this}. Splitting the two makes the transaction boundary real
 * rather than decorative.
 *
 * <p>Every method returns a DTO or a fully-loaded value, never a lazy entity.
 * Handing a proxy back to a caller outside the transaction is how
 * {@code LazyInitializationException} happens.
 */
@Service
public class KnowledgeStore {

    private final BotRepository botRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;

    public KnowledgeStore(
            BotRepository botRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository) {
        this.botRepository = botRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
    }

    // -------------------------------------------------------------- lookups --

    /** The bot, scoped to the caller's organization. */
    @Transactional(readOnly = true)
    public Bot requireBot(UUID botId, UUID organizationId) {
        return botRepository
                .findByIdAndOrganizationId(botId, organizationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Bot", botId));
    }

    /**
     * The AI-side identifiers for a bot's knowledge base.
     *
     * <p>A small record rather than the entity, so the caller can use it after
     * the transaction has closed.
     */
    @Transactional(readOnly = true)
    public Optional<KnowledgeBaseRef> findKnowledgeBaseRef(UUID botId, UUID organizationId) {
        return knowledgeBaseRepository
                .findByBotIdAndOrganizationId(botId, organizationId)
                .map(kb -> new KnowledgeBaseRef(kb.getId(), kb.getAiKnowledgeBaseId()));
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getKnowledgeBase(UUID botId, UUID organizationId) {
        KnowledgeBase knowledgeBase =
                knowledgeBaseRepository
                        .findByBotIdAndOrganizationId(botId, organizationId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "This bot has no knowledge base yet"));

        return KnowledgeBaseResponse.from(
                knowledgeBase, documentRepository.countByKnowledgeBaseId(knowledgeBase.getId()));
    }

    /** The document, scoped to the caller's organization. */
    @Transactional(readOnly = true)
    public DocumentRef requireDocumentRef(UUID documentId, UUID organizationId) {
        Document document =
                documentRepository
                        .findByIdAndOrganizationId(documentId, organizationId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Document", documentId));

        return new DocumentRef(
                document.getId(),
                document.getAiDocumentId(),
                document.getStatus(),
                DocumentResponse.from(document));
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> listDocuments(
            UUID botId, UUID organizationId, Pageable pageable) {
        return PageResponse.from(
                documentRepository.findAllByBotIdAndOrganizationId(botId, organizationId, pageable),
                DocumentResponse::from);
    }

    // --------------------------------------------------------------- writes --

    @Transactional
    public KnowledgeBaseResponse saveKnowledgeBase(
            UUID botId, String name, UUID aiKnowledgeBaseId, String externalRef) {

        Bot bot =
                botRepository
                        .findById(botId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Bot", botId));

        KnowledgeBase saved =
                knowledgeBaseRepository.save(
                        new KnowledgeBase(bot, name, aiKnowledgeBaseId, externalRef));

        return KnowledgeBaseResponse.from(saved, 0);
    }

    @Transactional
    public DocumentResponse saveDocument(
            UUID knowledgeBaseId,
            String filename,
            String contentType,
            long sizeBytes,
            UUID aiDocumentId,
            DocumentStatus status) {

        KnowledgeBase knowledgeBase =
                knowledgeBaseRepository
                        .findById(knowledgeBaseId)
                        .orElseThrow(
                                () ->
                                        ResourceNotFoundException.of(
                                                "KnowledgeBase", knowledgeBaseId));

        Document document =
                new Document(
                        knowledgeBase,
                        filename,
                        filename,
                        contentType,
                        // The column requires a positive size; a zero-byte file
                        // is rejected before reaching here.
                        Math.max(sizeBytes, 1));
        document.markAccepted(aiDocumentId, status);

        return DocumentResponse.from(documentRepository.save(document));
    }

    /** Copies the AI service's reported progress onto our mirror. */
    @Transactional
    public DocumentResponse applyStatus(
            UUID documentId,
            DocumentStatus status,
            String errorMessage,
            Integer chunkCount,
            Integer pageCount) {

        Document document =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Document", documentId));

        document.applyAiStatus(status, errorMessage, chunkCount, pageCount);
        return DocumentResponse.from(document);
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        documentRepository.deleteById(documentId);
    }

    // --------------------------------------------------------------- values --

    /** Enough to address a knowledge base on both sides of the boundary. */
    public record KnowledgeBaseRef(UUID id, UUID aiKnowledgeBaseId) {}

    /**
     * A document's identity and current state, safe to use outside a
     * transaction.
     */
    public record DocumentRef(
            UUID id, UUID aiDocumentId, DocumentStatus status, DocumentResponse response) {

        public boolean needsRefresh() {
            return status.isInProgress() && aiDocumentId != null;
        }
    }
}
