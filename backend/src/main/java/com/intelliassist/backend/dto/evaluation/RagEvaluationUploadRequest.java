package com.intelliassist.backend.dto.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/**
 * A results file produced by the Python evaluation runner.
 *
 * <p>Field names are snake_case because this is that file, posted verbatim —
 * asking an operator to reshape JSON before uploading it would be a step they
 * would eventually get wrong.
 *
 * @param schemaVersion the runner's output version, refused if unrecognised
 * @param metrics null values are meaningful: the dataset stated no expectation
 *     for that metric, which is not the same as scoring zero
 */
public record RagEvaluationUploadRequest(
        @JsonProperty("schema_version") int schemaVersion,
        @NotBlank @Size(max = 500) String dataset,
        @NotBlank @Size(max = 200) String model,
        @JsonProperty("started_at") @NotNull Instant startedAt,
        @JsonProperty("finished_at") @NotNull Instant finishedAt,
        @NotNull Totals totals,
        @NotNull Map<String, Double> metrics,
        @NotNull Map<String, Double> latency,
        @NotNull JsonNode cases) {

    /** The runner output this backend understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public record Totals(
            @PositiveOrZero int total,
            @PositiveOrZero int successful,
            @PositiveOrZero int failed,
            @JsonProperty("unmet_expectations") @PositiveOrZero int unmetExpectations) {}
}
