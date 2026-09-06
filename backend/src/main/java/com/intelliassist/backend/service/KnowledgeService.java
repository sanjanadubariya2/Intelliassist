package com.intelliassist.backend.service;

import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.dto.knowledge.DocumentResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeBaseResponse;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.DocumentStatus;
import com.intelliassist.backend.entity.MemberRole;
import com.intelliassist.backend.entity.KnowledgeBase;
import com.intelliassist.backend.exception.AccessDeniedException;
import com.intelliassist.backend.integration.ai.AiServiceClient;
import com.intelliassist.backend.integration.ai.AiServiceException;
import com.intelliassist.backend.integration.ai.dto.AiDocumentAcceptedResponse;
import com.intelliassist.backend.integration.ai.dto.AiDocumentResponse;
import com.intelliassist.backend.integration.ai.dto.AiKnowledgeBaseResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge bases and documents, and the handoff to the AI service.
 *
 * <p>The hard part here is that a database transaction and an HTTP call to
 * another service cannot be made atomic. Two rules keep that honest:
 *
 * <ol>
 *   <li><strong>No transaction is held across an HTTP call.</strong> A 50 MB
 *       upload can take a minute; holding a pooled connection for that would
 *       exhaust the pool under mild load. This class is deliberately
 *       <em>not</em> transactional — {@link KnowledgeStore} owns every
 *       transaction, and each one is short.
 *   <li><strong>Our row is written only after the AI service accepts.</strong>
 *       A local row pointing at nothing is worse than no row: it shows up in
 *       the dashboard, never finishes, and cannot be diagnosed.
 * </ol>
 *
 * <p>The residual risk is the reverse case — the AI service accepts and our
 * write then fails — which leaves an orphaned corpus there. That is why
 * {@code external_ref} is stored: it is enough to find and reclaim such a
 * corpus. Making this genuinely atomic would need an outbox, which is more
 * machinery than this system's failure rate justifies.
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final KnowledgeStore store;
    private final AiServiceClient aiServiceClient;

    public KnowledgeService(KnowledgeStore store, AiServiceClient aiServiceClient) {
        this.store = store;
        this.aiServiceClient = aiServiceClient;
    }

    // ------------------------------------------------------ knowledge base --

    /**
     * Returns the bot's knowledge base, creating it in the AI service on first
     * use.
     *
     * <p>Created on demand rather than alongside the bot, so a bot that never
     * receives a document costs nothing on the AI side.
     */
    public KnowledgeBaseResponse ensureKnowledgeBase(
            AuthenticatedUser caller, UUID botId, String requestedName) {

        requireRole(caller, MemberRole.ADMIN);

        // Confirms ownership before anything else happens.
        Bot bot = store.requireBot(botId, caller.organizationId());

        var existing = store.findKnowledgeBaseRef(botId, caller.organizationId());
        if (existing.isPresent()) {
            return store.getKnowledgeBase(botId, caller.organizationId());
        }

        String name =
                requestedName == null || requestedName.isBlank()
                        ? bot.getName()
                        : requestedName.strip();
        String externalRef = KnowledgeBase.externalRefFor(botId);

        AiKnowledgeBaseResponse created =
                aiServiceClient.createKnowledgeBase(externalRef, name, bot.getDescription());

        log.info(
                "created AI knowledge base {} for bot {} (external_ref={})",
                created.id(),
                botId,
                externalRef);

        return store.saveKnowledgeBase(botId, name, created.id(), externalRef);
    }

    public KnowledgeBaseResponse getKnowledgeBase(AuthenticatedUser caller, UUID botId) {
        return store.getKnowledgeBase(botId, caller.organizationId());
    }

    /**
     * Removes a bot's corpus from the AI service.
     *
     * <p>Called when a bot is deleted. Our own rows disappear by cascade, but
     * the AI service knows nothing about that - without this call its knowledge
     * base, chunks and embeddings would survive with no row left pointing at
     * them: storage paid for forever, reachable by nothing.
     *
     * <p>No role check here: the caller has already established ADMIN. Doing it
     * twice would only invite the two rules to drift apart.
     *
     * <p>Idempotent, because the AI service treats deleting an absent knowledge
     * base as success - so a bot delete that failed halfway can simply be
     * retried.
     */
    public void deleteKnowledgeBaseForBot(UUID botId, UUID organizationId) {
        var existing = store.findKnowledgeBaseRef(botId, organizationId);
        if (existing.isEmpty()) {
            return;
        }

        UUID aiKnowledgeBaseId = existing.get().aiKnowledgeBaseId();
        aiServiceClient.deleteKnowledgeBase(aiKnowledgeBaseId);
        log.info("deleted AI knowledge base {} for bot {}", aiKnowledgeBaseId, botId);
    }

    // ----------------------------------------------------------- documents --

    /**
     * Uploads a document and records it.
     *
     * <p>Returns while ingestion is still running. The AI service answers 202
     * and a worker performs extraction, chunking and embedding; blocking here
     * until {@code INDEXED} would occupy a request thread for minutes on a
     * large PDF and time out at the gateway.
     */
    public DocumentResponse uploadDocument(
            AuthenticatedUser caller, UUID botId, MultipartFile file) {

        requireRole(caller, MemberRole.ADMIN);

        String contentType = FileUploadValidator.validate(file);
        String filename = FileUploadValidator.sanitizeFilename(file.getOriginalFilename());

        // Auto-created on first upload so a client need not make two calls in
        // the right order.
        UUID aiKnowledgeBaseId;
        UUID knowledgeBaseId;
        var existing = store.findKnowledgeBaseRef(botId, caller.organizationId());
        if (existing.isPresent()) {
            aiKnowledgeBaseId = existing.get().aiKnowledgeBaseId();
            knowledgeBaseId = existing.get().id();
        } else {
            ensureKnowledgeBase(caller, botId, null);
            var created = store.findKnowledgeBaseRef(botId, caller.organizationId()).orElseThrow();
            aiKnowledgeBaseId = created.aiKnowledgeBaseId();
            knowledgeBaseId = created.id();
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.error("could not read uploaded file for bot {}", botId, exception);
            throw new FileUploadValidator.InvalidUploadException(
                    HttpStatus.BAD_REQUEST,
                    "UPLOAD_READ_FAILED",
                    "The uploaded file could not be read");
        }

        AiDocumentAcceptedResponse accepted =
                aiServiceClient.uploadDocument(aiKnowledgeBaseId, filename, contentType, content);

        if (accepted.duplicate()) {
            // Not an error. The AI service hashes content and returned the
            // document it already had, which is what makes a client retry safe.
            log.info("document '{}' was already present in the AI knowledge base", filename);
        }

        DocumentResponse saved =
                store.saveDocument(
                        knowledgeBaseId,
                        filename,
                        contentType,
                        file.getSize(),
                        accepted.id(),
                        DocumentStatus.fromAiStatus(accepted.status()));

        log.info("accepted document {} (ai_document_id={}) for bot {}", saved.id(), accepted.id(), botId);
        return saved;
    }

    /**
     * Document status, refreshed from the AI service while ingestion is in
     * progress.
     *
     * <p>Reconciled on read rather than by a background poller: the dashboard
     * asks when somebody is watching, which is exactly when a fresh answer is
     * worth a request. A poller would spend calls on documents nobody has open.
     */
    public DocumentResponse getDocument(AuthenticatedUser caller, UUID documentId) {
        var ref = store.requireDocumentRef(documentId, caller.organizationId());

        if (!ref.needsRefresh()) {
            return ref.response();
        }

        try {
            AiDocumentResponse remote = aiServiceClient.getDocument(ref.aiDocumentId());
            return store.applyStatus(
                    ref.id(),
                    DocumentStatus.fromAiStatus(remote.status()),
                    remote.errorMessage(),
                    remote.chunkCount(),
                    remote.pageCount());

        } catch (AiServiceException exception) {
            // The last known state is more useful than an error page while
            // ingestion carries on perfectly well on the other side.
            log.warn(
                    "could not refresh status for document {}: {}",
                    documentId,
                    exception.getUpstreamCode());
            return ref.response();
        }
    }

    public PageResponse<DocumentResponse> listDocuments(
            AuthenticatedUser caller, UUID botId, Pageable pageable) {

        store.requireBot(botId, caller.organizationId());
        return store.listDocuments(botId, caller.organizationId(), pageable);
    }

    /**
     * Deletes a document from the AI service and then from here.
     *
     * <p>Remote first, deliberately. If the AI call fails we keep our row and
     * report the failure, so the delete can be retried. Removing our row first
     * would strand a corpus in the AI service with nothing left to identify it.
     */
    public void deleteDocument(AuthenticatedUser caller, UUID documentId) {
        requireRole(caller, MemberRole.ADMIN);

        var ref = store.requireDocumentRef(documentId, caller.organizationId());

        if (ref.aiDocumentId() != null) {
            aiServiceClient.deleteDocument(ref.aiDocumentId());
        }
        store.deleteDocument(ref.id());

        log.info("deleted document {} (ai_document_id={})", documentId, ref.aiDocumentId());
    }

    private static void requireRole(AuthenticatedUser caller, MemberRole required) {
        if (!caller.hasAtLeast(required)) {
            throw AccessDeniedException.insufficientRole();
        }
    }
}
