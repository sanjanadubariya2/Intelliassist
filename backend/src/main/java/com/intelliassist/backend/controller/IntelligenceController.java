package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.insights.ConversationInsightsResponse;
import com.intelliassist.backend.dto.knowledge.KnowledgeHealthResponse;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.ConversationInsightsService;
import com.intelliassist.backend.service.KnowledgeHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only analysis of a bot: what visitors ask it, and how healthy the
 * knowledge base it answers from is.
 *
 * <p>Readable by any member, like usage: understanding a bot does not require
 * the right to change it.
 */
@Tag(name = "Intelligence", description = "Conversation insights and knowledge base health")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/bots/{botId}")
public class IntelligenceController {

    private final ConversationInsightsService insightsService;
    private final KnowledgeHealthService healthService;

    public IntelligenceController(
            ConversationInsightsService insightsService, KnowledgeHealthService healthService) {
        this.insightsService = insightsService;
        this.healthService = healthService;
    }

    @Operation(
            summary = "Conversation intelligence",
            description =
                    "Most-asked questions, unanswered questions, and daily resolution outcomes."
                            + " Defaults to the last 30 days (UTC).")
    @GetMapping("/insights")
    public ResponseEntity<ConversationInsightsResponse> insights(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {

        return ResponseEntity.ok(insightsService.insights(caller, botId, from, to));
    }

    @Operation(
            summary = "Knowledge base health",
            description =
                    "Failed, stuck, outdated, duplicate and conflicting documents, plus FAQs"
                            + " suggested by questions the bot could not answer.")
    @GetMapping("/knowledge/health")
    public ResponseEntity<KnowledgeHealthResponse> health(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {

        return ResponseEntity.ok(healthService.check(caller, botId));
    }
}
