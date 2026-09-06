-- Stored results of a RAG evaluation run.
--
-- Evaluation is run separately, against the Python service, and costs real
-- model calls. The dashboard reads the last stored run rather than triggering
-- one: a page load must never start a job that takes minutes and bills money.
--
-- Runs are kept rather than overwritten. The single most useful thing a
-- quality metric can tell you is which direction it is moving, and that needs
-- the previous run to still exist.

CREATE TABLE rag_evaluation_runs (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    bot_id             UUID        NOT NULL,

    -- Named so a run can be identified later ("nightly", "before reranker").
    dataset            VARCHAR(500) NOT NULL,
    model              VARCHAR(200) NOT NULL,

    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ NOT NULL,

    total_questions    INTEGER     NOT NULL,
    successful         INTEGER     NOT NULL,
    failed             INTEGER     NOT NULL,
    unmet_expectations INTEGER     NOT NULL,

    -- The metric block, stored whole rather than as columns.
    --
    -- Metrics are the part of this feature most likely to change: adding one
    -- should not need a migration, and a stored run from before a metric
    -- existed should keep meaning what it meant. A column per metric would
    -- force every historic row to gain a NULL that reads as a zero.
    metrics            JSONB       NOT NULL,
    latency            JSONB       NOT NULL,

    -- Per-question detail, so the dashboard can show which questions failed
    -- rather than only an average that hides them.
    cases              JSONB       NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_rag_eval_bot FOREIGN KEY (bot_id) REFERENCES bots (id) ON DELETE CASCADE,

    CONSTRAINT ck_rag_eval_counts CHECK (
        total_questions >= 0 AND successful >= 0 AND failed >= 0
        AND unmet_expectations >= 0
        AND successful + failed = total_questions
    )
);

-- Every read is "the latest run for this bot".
CREATE INDEX ix_rag_eval_bot_finished ON rag_evaluation_runs (bot_id, finished_at DESC);

COMMENT ON COLUMN rag_evaluation_runs.metrics IS
    'hit_at_k, mrr, source_recall, source_precision, answer_token_f1, groundedness, answer_rate, abstention_accuracy. Null values mean the dataset stated no expectation for that metric.';
COMMENT ON COLUMN rag_evaluation_runs.cases IS
    'Per-question outcomes, including the questions that did not meet expectations.';
