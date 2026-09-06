package com.intelliassist.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One stored RAG evaluation run.
 *
 * <p>Evaluation happens outside this service: the Python runner executes a
 * dataset against the real pipeline and produces a JSON report, which is
 * uploaded here. Spring Boot stores and serves it and computes none of it —
 * recomputing metrics on this side would mean two implementations of the same
 * arithmetic, and they would disagree.
 *
 * <p>Runs accumulate rather than replacing one another. A quality number in
 * isolation says very little; the direction it moved between two runs is the
 * part worth acting on.
 */
@Entity
@Table(name = "rag_evaluation_runs")
public class RagEvaluationRun {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    /**
     * Lazy, and never returned from a controller.
     *
     * <p>The bot is here to scope the run and to cascade its deletion; nothing
     * about the bot belongs in an evaluation response.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bot_id", nullable = false)
    private Bot bot;

    @Column(nullable = false, length = 500)
    private String dataset;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "total_questions", nullable = false)
    private int totalQuestions;

    @Column(nullable = false)
    private int successful;

    @Column(nullable = false)
    private int failed;

    @Column(name = "unmet_expectations", nullable = false)
    private int unmetExpectations;

    /**
     * The metric block, stored as it arrived.
     *
     * <p>{@code Double} rather than {@code double} throughout: a metric is null
     * when the dataset stated no expectation for it, and collapsing that to
     * zero would report a perfect-scoring run as a failing one.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Double> metrics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Double> latency;

    /** Per-question outcomes, so the dashboard can name what failed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String cases;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RagEvaluationRun() {
        // JPA.
    }

    public RagEvaluationRun(
            Bot bot,
            String dataset,
            String model,
            Instant startedAt,
            Instant finishedAt,
            int totalQuestions,
            int successful,
            int failed,
            int unmetExpectations,
            Map<String, Double> metrics,
            Map<String, Double> latency,
            String cases) {
        this.bot = bot;
        this.dataset = dataset;
        this.model = model;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.totalQuestions = totalQuestions;
        this.successful = successful;
        this.failed = failed;
        this.unmetExpectations = unmetExpectations;
        this.metrics = metrics;
        this.latency = latency;
        this.cases = cases;
    }

    public UUID getId() {
        return id;
    }

    public Bot getBot() {
        return bot;
    }

    public String getDataset() {
        return dataset;
    }

    public String getModel() {
        return model;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public int getSuccessful() {
        return successful;
    }

    public int getFailed() {
        return failed;
    }

    public int getUnmetExpectations() {
        return unmetExpectations;
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }

    public Map<String, Double> getLatency() {
        return latency;
    }

    public String getCases() {
        return cases;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
