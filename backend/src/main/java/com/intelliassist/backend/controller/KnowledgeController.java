package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.dto.knowledge.CreateKnowledgeBaseRequest;
import com.intelliassist.backend.dto.knowledge.DocumentResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeBaseResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge base and document upload, addressed by bot.
 *
 * <p>Every route is under {@code /bots/{botId}}, so ownership is checked by the
 * same rule as everything else: the bot must belong to the caller's
 * organization. No route accepts a knowledge base id from the client.
 */
@RestController
@RequestMapping("/api/v1/bots/{botId}")
@Validated
public class KnowledgeController {

    private static final int MAX_PAGE_SIZE = 100;

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * Creates the bot's knowledge base, or returns the existing one.
     *
     * <p>Idempotent rather than 409-on-repeat: a bot has exactly one knowledge
     * base, so "make sure it exists" is the only useful operation, and it lets
     * a client call this without first checking.
     */
    @PostMapping("/knowledge")
    public ResponseEntity<KnowledgeBaseResponse> createKnowledgeBase(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody(required = false) CreateKnowledgeBaseRequest request) {

        String name = request == null ? null : request.name();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(knowledgeService.ensureKnowledgeBase(caller, botId, name));
    }

    @GetMapping("/knowledge")
    public ResponseEntity<KnowledgeBaseResponse> getKnowledgeBase(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {
        return ResponseEntity.ok(knowledgeService.getKnowledgeBase(caller, botId));
    }

    /**
     * Uploads a document.
     *
     * <p>Returns {@code 202}, mirroring the AI service: the file has been
     * accepted and ingestion is running. The document becomes answerable only
     * when its status reaches {@code INDEXED}, which the client discovers by
     * polling {@code GET /api/v1/documents/{id}}.
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @RequestPart("file") MultipartFile file) {

        DocumentResponse accepted = knowledgeService.uploadDocument(caller, botId, file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    @GetMapping("/documents")
    public ResponseEntity<PageResponse<DocumentResponse>> listDocuments(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(knowledgeService.listDocuments(caller, botId, pageable));
    }
}
