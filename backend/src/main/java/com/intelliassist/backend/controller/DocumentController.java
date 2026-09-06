package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.knowledge.DocumentResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.KnowledgeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document status and deletion, addressed by document id.
 *
 * <p>Separate from {@link KnowledgeController} because these routes are not
 * nested under a bot: a client polling a status already holds the document id
 * and should not have to remember which bot it came from. Ownership is still
 * resolved through the document's knowledge base to its bot's organization.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final KnowledgeService knowledgeService;

    public DocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * Ingestion status.
     *
     * <p>This is the endpoint a client polls after a {@code 202}. While the
     * document is still in progress the status is refreshed from the AI service
     * on each call, so the answer is current rather than whatever was true at
     * upload time.
     */
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID documentId) {
        return ResponseEntity.ok(knowledgeService.getDocument(caller, documentId));
    }

    /** Removes the document from the AI service and then from here. */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID documentId) {
        knowledgeService.deleteDocument(caller, documentId);
        return ResponseEntity.noContent().build();
    }
}
