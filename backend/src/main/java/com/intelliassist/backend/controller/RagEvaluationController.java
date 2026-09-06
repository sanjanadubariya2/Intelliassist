package com.intelliassist.backend.controller;

import com.intelliassist.backend.dto.evaluation.RagEvaluationResponse;
import com.intelliassist.backend.dto.evaluation.RagEvaluationUploadRequest;
import com.intelliassist.backend.security.AuthenticatedUser;
import com.intelliassist.backend.service.RagEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG evaluation results for a bot.
 *
 * <p>Reading is cheap; running is not. Evaluation executes separately against
 * the Python service and uploads its results here, so a dashboard request only
 * ever reads the last stored run. There is deliberately no endpoint that
 * triggers a run: it takes minutes and costs real model calls, and a page load
 * has no business starting one.
 */
@Tag(name = "Analytics", description = "RAG quality measurements")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/bots/{botId}/analytics/rag-evaluation")
public class RagEvaluationController {

    private final RagEvaluationService evaluationService;

    public RagEvaluationController(RagEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @Operation(
            summary = "The latest RAG evaluation for a bot",
            description =
                    "Returns the most recent stored evaluation run, or 204 when the bot has "
                            + "never been evaluated. Metric values may be null, meaning the "
                            + "dataset stated no expectation for that metric — render those as "
                            + "\"n/a\" rather than zero.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The latest run"),
        @ApiResponse(responseCode = "204", description = "The bot has never been evaluated"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
        @ApiResponse(responseCode = "404", description = "No such bot in this organization")
    })
    @GetMapping
    public ResponseEntity<RagEvaluationResponse> latest(
            @AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID botId) {

        return evaluationService
                .latest(caller, botId)
                .map(ResponseEntity::ok)
                // 204 rather than an empty object: "never evaluated" and "an
                // evaluation that scored nothing" must not look the same.
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "Record an evaluation run",
            description =
                    "Accepts a results file produced by `python -m app.services.evaluation`. "
                            + "Runs accumulate rather than replacing one another, because the "
                            + "direction a metric moved matters more than its absolute value.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Stored"),
        @ApiResponse(responseCode = "400", description = "Unsupported schema or inconsistent totals"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid token"),
        @ApiResponse(responseCode = "404", description = "No such bot in this organization")
    })
    @PostMapping
    public ResponseEntity<RagEvaluationResponse> record(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID botId,
            @Valid @RequestBody RagEvaluationUploadRequest request) {

        RagEvaluationResponse stored = evaluationService.record(caller, botId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }
}
