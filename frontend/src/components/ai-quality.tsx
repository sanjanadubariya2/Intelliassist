"use client";

import { useState } from "react";
import { AlertTriangle, CheckCircle2, HelpCircle, XCircle } from "lucide-react";
import {
  api,
  type EvaluationCase,
  type EvaluationMetric,
  type RagEvaluation,
} from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Badge,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  cx,
  formatRelative,
} from "@/components/ui";

/**
 * How each metric is explained to someone who does not work on retrieval.
 *
 * Written as plain statements of what the number means, because the audience
 * for this screen is a business owner deciding whether their bot is good
 * enough, not an ML engineer tuning it.
 */
const EXPLANATIONS: Record<
  EvaluationMetric,
  { label: string; short: string; detail: string; goodIsHigh: boolean }
> = {
  hit_at_k: {
    label: "Found the right document",
    short: "Hit@5",
    detail:
      "How often the correct document appeared in the sources the bot pulled up. If this is low, the bot is looking in the wrong place and nothing downstream can fix it.",
    goodIsHigh: true,
  },
  mrr: {
    label: "Ranked it near the top",
    short: "MRR",
    detail:
      "Whether the right document came first rather than merely somewhere. First place scores 1.0, second 0.5, third 0.33. A high hit rate with a low score here means the bot finds the answer but buries it.",
    goodIsHigh: true,
  },
  source_recall: {
    label: "Found every document",
    short: "Source recall",
    detail:
      "When an answer needs two documents, whether both were cited. Lower than the hit rate means answers are partially sourced.",
    goodIsHigh: true,
  },
  source_precision: {
    label: "Cited only what it used",
    short: "Source precision",
    detail:
      "Of the documents cited, how many were actually relevant. Low means answers are padded with sources that did not contribute, which makes citations less trustworthy to read.",
    goodIsHigh: true,
  },
  groundedness: {
    label: "Backed by a document",
    short: "Groundedness",
    detail:
      "Of the questions the bot chose to answer, how many cited at least one source. Anything below 100% means it answered from something other than your documents.",
    goodIsHigh: true,
  },
  answer_rate: {
    label: "Answered rather than declined",
    short: "Answer rate",
    detail:
      "How often it gave an answer instead of saying it did not know. Read this next to groundedness: a bot that declines everything scores perfectly on groundedness and helps nobody.",
    goodIsHigh: true,
  },
  abstention_accuracy: {
    label: "Knew when to decline",
    short: "Abstention accuracy",
    detail:
      "On questions your documents genuinely cannot answer, whether it declined instead of inventing something. This is the metric that catches confident nonsense.",
    goodIsHigh: true,
  },
  answer_token_f1: {
    label: "Wording overlap",
    short: "Token F1",
    detail:
      "How many words the answer shares with a reference answer. This is a rough regression signal, not a correctness score — a correct answer phrased differently scores badly, and nothing here understands meaning. Watch it move between runs; do not read it as accuracy.",
    goodIsHigh: true,
  },
};

/** The order they read best in: retrieval first, then behaviour, then the caveat. */
const ORDER: EvaluationMetric[] = [
  "hit_at_k",
  "mrr",
  "groundedness",
  "answer_rate",
  "abstention_accuracy",
  "source_precision",
  "source_recall",
  "answer_token_f1",
];

export function AiQuality({ botId }: { botId: string }) {
  const { data, loading, error, reload } = useAsync(
    () => api.evaluation.latest(botId),
    [botId],
  );

  if (loading) return <Loading label="Loading quality results" />;
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!data) return <NeverEvaluated />;

  return <Results run={data} />;
}

function NeverEvaluated() {
  return (
    <Card>
      <CardHeader
        title="AI quality"
        description="How well this bot answers a fixed set of test questions."
      />
      <Empty
        icon={<HelpCircle className="h-7 w-7" />}
        title="No evaluation yet"
        description="Evaluation runs separately against a dataset of questions with known answers — it costs real model calls, so it is never triggered by opening this page."
      />
      <div className="border-t border-line px-5 py-4">
        <p className="mb-2 text-[12.5px] text-muted">Run one:</p>
        <pre className="overflow-x-auto rounded-lg bg-ink px-4 py-3 text-[12px] leading-relaxed text-white/90">
          <code>{`cd ai-service
python -m app.services.evaluation \\
  --dataset evaluation/acme.example.jsonl \\
  --knowledge-base-id <id> \\
  --output results.json`}</code>
        </pre>
        <p className="mt-2 text-[12.5px] text-muted">
          Then POST the results file to this bot&apos;s{" "}
          <code className="rounded bg-canvas px-1 py-0.5 text-[11.5px]">
            /analytics/rag-evaluation
          </code>{" "}
          endpoint. See <code className="text-[11.5px]">docs/RAG_EVALUATION.md</code>.
        </p>
      </div>
    </Card>
  );
}

function Results({ run }: { run: RagEvaluation }) {
  const failures = run.cases.filter(
    (item) => !item.ok || item.hit_at_k === 0 || item.abstained === true,
  );

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          title="AI quality"
          description={`${run.totalQuestions} test questions · ${run.model}`}
          action={
            <span className="text-[12px] text-muted">
              Last run {formatRelative(run.finishedAt)}
            </span>
          }
        />

        <div className="grid grid-cols-1 gap-px bg-line sm:grid-cols-2 lg:grid-cols-4">
          {ORDER.filter((key) => key in run.metrics).map((key) => (
            <Metric key={key} metric={key} value={run.metrics[key] ?? null} />
          ))}
        </div>

        <div className="flex flex-wrap gap-6 border-t border-line px-5 py-4 text-[13px]">
          <Figure label="Average response" value={ms(run.latency.mean_ms)} />
          <Figure label="Slowest 5%" value={ms(run.latency.p95_ms)} />
          <Figure
            label="Questions that errored"
            value={String(run.failed)}
            tone={run.failed > 0 ? "bad" : undefined}
          />
          <Figure
            label="Did not meet expectations"
            value={String(run.unmetExpectations)}
            tone={run.unmetExpectations > 0 ? "warn" : undefined}
          />
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Questions worth looking at"
          description="Where the bot missed the expected document, declined to answer, or errored."
        />
        {failures.length === 0 ? (
          <Empty
            icon={<CheckCircle2 className="h-7 w-7 text-ok" />}
            title="Every question met its expectations"
          />
        ) : (
          <ul className="divide-y divide-line">
            {failures.map((item) => (
              <FailedCase key={item.id} item={item} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function Metric({ metric, value }: { metric: EvaluationMetric; value: number | null }) {
  const [open, setOpen] = useState(false);
  const info = EXPLANATIONS[metric];

  // Null is not zero: the dataset asked nothing about this metric. Showing 0%
  // would read as a failing score for a question nobody posed.
  const shown = value === null ? "n/a" : `${Math.round(value * 100)}%`;

  const tone =
    value === null
      ? "text-faint"
      : value >= 0.9
        ? "text-ok"
        : value >= 0.7
          ? "text-warn"
          : "text-bad";

  return (
    <div className="bg-surface p-5">
      <button
        onClick={() => setOpen((current) => !current)}
        className="group flex w-full items-start justify-between gap-2 text-left"
        aria-expanded={open}
      >
        <span className="text-[13px] text-muted">{info.label}</span>
        <HelpCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-faint group-hover:text-accent" />
      </button>

      <p className={cx("mt-1.5 text-2xl font-semibold tracking-tight", tone)}>{shown}</p>
      <p className="text-[11.5px] text-faint">{info.short}</p>

      {open && <p className="mt-2 text-[12px] leading-relaxed text-muted">{info.detail}</p>}
    </div>
  );
}

function Figure({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "warn" | "bad";
}) {
  return (
    <div>
      <p className="text-[12px] text-muted">{label}</p>
      <p
        className={cx(
          "font-semibold",
          tone === "bad" ? "text-bad" : tone === "warn" ? "text-warn" : "text-ink",
        )}
      >
        {value}
      </p>
    </div>
  );
}

function FailedCase({ item }: { item: EvaluationCase }) {
  return (
    <li className="px-5 py-3.5">
      <div className="flex items-start gap-2.5">
        {!item.ok ? (
          <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-bad" />
        ) : (
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warn" />
        )}

        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium">{item.question}</p>

          {!item.ok ? (
            <p className="mt-1 text-[12.5px] text-bad">{item.error}</p>
          ) : (
            <>
              {item.hit_at_k === 0 && (
                <p className="mt-1 text-[12.5px] text-muted">
                  Expected {item.expected_sources?.join(", ") || "a source"} — cited{" "}
                  {item.cited_sources?.length
                    ? item.cited_sources.join(", ")
                    : "nothing"}
                </p>
              )}
              {item.abstained && item.hit_at_k !== 0 && (
                <p className="mt-1 text-[12.5px] text-muted">
                  The bot declined to answer this one.
                </p>
              )}
            </>
          )}
        </div>

        <Badge tone={!item.ok ? "bad" : "warn"}>
          {!item.ok ? "Errored" : item.hit_at_k === 0 ? "Wrong source" : "Declined"}
        </Badge>
      </div>
    </li>
  );
}

function ms(value: number | undefined) {
  if (value === undefined) return "—";
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${Math.round(value)}ms`;
}
