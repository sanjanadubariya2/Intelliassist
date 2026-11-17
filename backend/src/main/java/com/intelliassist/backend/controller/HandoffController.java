package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.chat.ConversationResponse;
import com.intelliassist.backend.dto.common.PageResponse;
import com.intelliassist.backend.dto.handoff.AssignConversationRequest;
import com.intelliassist.backend.dto.handoff.InboxSummaryResponse;
import com.intelliassist.backend.dto.handoff.InboxView;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.HandoffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The human handoff inbox.
 *
 * <p>Organization-wide: the queue spans every bot, because one support team
 * answers for all of them.
 */
@Tag(name = "Handoff", description = "Escalated conversations and who is handling them")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1")
@Validated
public class HandoffController {

    private final HandoffService handoffService;

    public HandoffController(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @Operation(summary = "Conversations in one view of the inbox")
    @GetMapping("/handoff/conversations")
    public ResponseEntity<PageResponse<ConversationResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(defaultValue = "OPEN") InboxView view,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {

        return ResponseEntity.ok(handoffService.list(caller, view, page, size));
    }

    @Operation(summary = "Inbox counts and median time to resolution")
    @GetMapping("/handoff/summary")
    public ResponseEntity<InboxSummaryResponse> summary(
            @AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(handoffService.summary(caller));
    }

    @Operation(
            summary = "Assign a conversation to a teammate",
            description =
                    "A null assigneeId returns it to the shared queue. Assigning an open"
                            + " conversation escalates it.")
    @PatchMapping("/conversations/{conversationId}/assignee")
    public ResponseEntity<ConversationResponse> assign(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID conversationId,
            @RequestBody AssignConversationRequest request) {

        return ResponseEntity.ok(
                handoffService.assign(caller, conversationId, request.assigneeId()));
    }
}
