package com.intelliassist.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelliassist.backend.dto.evaluation.RagEvaluationResponse;
import com.intelliassist.backend.dto.evaluation.RagEvaluationUploadRequest;
import com.intelliassist.backend.entity.Bot;
import com.intelliassist.backend.entity.RagEvaluationRun;
import com.intelliassist.backend.exception.ApiException;
import com.intelliassist.backend.exception.ResourceNotFoundException;
import com.intelliassist.backend.repository.BotRepository;
import com.intelliassist.backend.repository.RagEvaluationRunRepository;
import com.intelliassist.backend.security.AuthenticatedUser;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and serves RAG evaluation results.
 *
 * <p>It computes nothing. The Python runner executes the dataset against the
 * real pipeline and produces the numbers; duplicating that arithmetic here
 * would create a second implementation that quietly disagrees with the first.
 *
 * <p>Evaluation is deliberately not triggered from this side. A run costs
 * minutes and real model calls, and a dashboard request that starts one would
 * either time out or bill a customer for opening a page.
 */
@Service
public class RagEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationService.class);

    private final RagEvaluationRunRepository runRepository;
    private final BotRepository botRepository;
    private final ObjectMapper objectMapper;

    public RagEvaluationService(
            RagEvaluationRunRepository runRepository,
            BotRepository botRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.botRepository = botRepository;
        this.objectMapper = objectMapper;
    }

    /** The most recent run, or empty when the bot has never been evaluated. */
    @Transactional(readOnly = true)
    public Optional<RagEvaluationResponse> latest(AuthenticatedUser caller, UUID botId) {
        requireOwnBot(caller, botId);

        return runRepository
                .findMostRecent(botId, caller.organizationId())
                .map(this::toResponse);
    }

    /** Records a results file produced by the Python runner. */
    @Transactional
    public RagEvaluationResponse record(
            AuthenticatedUser caller, UUID botId, RagEvaluationUploadRequest request) {

        Bot bot = requireOwnBot(caller, botId);

        if (request.schemaVersion() != RagEvaluationUploadRequest.SUPPORTED_SCHEMA_VERSION) {
            // Refused rather than best-effort parsed: a silently misread metric
            // is worse than no metric, because someone will act on it.
            throw new UnsupportedSchemaException(request.schemaVersion());
        }

        if (request.totals().successful() + request.totals().failed()
                != request.totals().total()) {
            throw new InconsistentTotalsException();
        }

        String cases;
        try {
            cases = objectMapper.writeValueAsString(request.cases());
        } catch (Exception exception) {
            throw new UnreadableCasesException();
        }

        RagEvaluationRun saved =
                runRepository.save(
                        new RagEvaluationRun(
                                bot,
                                request.dataset(),
                                request.model(),
                                request.startedAt(),
                                request.finishedAt(),
                                request.totals().total(),
                                request.totals().successful(),
                                request.totals().failed(),
                                request.totals().unmetExpectations(),
                                request.metrics(),
                                request.latency(),
                                cases));

        log.info(
                "recorded evaluation run {} for bot {} ({} questions, {} failed)",
                saved.getId(),
                botId,
                saved.getTotalQuestions(),
                saved.getFailed());

        return toResponse(saved);
    }

    /**
     * Confirms the bot belongs to the caller's organization.
     *
     * <p>Reported as "not found" rather than "forbidden", like every other
     * cross-tenant access: a 403 would confirm the id exists.
     */
    private Bot requireOwnBot(AuthenticatedUser caller, UUID botId) {
        return botRepository
                .findByIdAndOrganizationId(botId, caller.organizationId())
                .orElseThrow(() -> ResourceNotFoundException.of("Bot", botId));
    }

    private RagEvaluationResponse toResponse(RagEvaluationRun run) {
        try {
            return new RagEvaluationResponse(
                    run.getId(),
                    run.getBot().getId(),
                    run.getDataset(),
                    run.getModel(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getTotalQuestions(),
                    run.getSuccessful(),
                    run.getFailed(),
                    run.getUnmetExpectations(),
                    run.getMetrics(),
                    run.getLatency(),
                    objectMapper.readTree(run.getCases()));
        } catch (Exception exception) {
            // Stored JSON that will not parse means the row is corrupt. Failing
            // loudly beats returning a run with its per-question detail
            // silently missing.
            throw new CorruptEvaluationException();
        }
    }

    /** A results file this version does not know how to read. */
    public static class UnsupportedSchemaException extends ApiException {
        public UnsupportedSchemaException(int found) {
            super(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_SCHEMA",
                    "Evaluation schema version %d is not supported (expected %d)"
                            .formatted(found, RagEvaluationUploadRequest.SUPPORTED_SCHEMA_VERSION));
        }
    }

    /** Totals that do not add up, which means the file was edited by hand. */
    public static class InconsistentTotalsException extends ApiException {
        public InconsistentTotalsException() {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INCONSISTENT_TOTALS",
                    "successful + failed must equal total");
        }
    }

    /** Per-question results that are not serialisable JSON. */
    public static class UnreadableCasesException extends ApiException {
        public UnreadableCasesException() {
            super(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CASES",
                    "The per-question results could not be read");
        }
    }

    /** A stored row whose JSON no longer parses. */
    public static class CorruptEvaluationException extends ApiException {
        public CorruptEvaluationException() {
            super(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "CORRUPT_EVALUATION",
                    "The stored evaluation results could not be read");
        }
    }
}
