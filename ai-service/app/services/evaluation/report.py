"""Evaluation results.

Separate from the runner because these shapes are the contract: they are what
gets written to JSON, read back by Spring Boot, and rendered in the dashboard.
The runner can change how it produces them without changing what a stored
result file means.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from typing import Any

#: Bumped when the JSON shape changes incompatibly, so a reader can refuse a
#: file it does not understand rather than silently misinterpreting it.
SCHEMA_VERSION = 1


@dataclass(frozen=True, slots=True)
class CaseOutcome:
    """One evaluated question."""

    id: str
    question: str

    #: False when the pipeline raised. Every metric below is then meaningless
    #: and is excluded from the averages rather than counted as zero.
    ok: bool
    error: str | None = None

    answer: str = ""
    #: The pipeline's own flag: retrieval cleared the relevance threshold.
    #: Kept for transparency, but *not* what `groundedness` reports — see
    #: EvalReport.metrics for why the two differ.
    retrieval_grounded: bool = False
    #: The bot declined instead of answering.
    abstained: bool = False

    expected_sources: list[str] = field(default_factory=list)
    cited_sources: list[str] = field(default_factory=list)

    #: Null when the case supplied no expectation for that metric. Null and
    #: zero mean very different things, and averaging them together would
    #: quietly punish a dataset for the questions it declined to constrain.
    hit_at_k: float | None = None
    mrr: float | None = None
    source_recall: float | None = None
    source_precision: float | None = None
    answer_token_f1: float | None = None
    abstention_correct: bool | None = None

    latency_ms: float = 0.0

    @property
    def failed_expectation(self) -> bool:
        """True when the case ran but did not meet what it asked for.

        Distinct from `ok`: this is the pipeline being wrong, not broken.
        """
        if not self.ok:
            return False
        if self.abstention_correct is False:
            return True
        # Retrieval missed the document the case named.
        return self.hit_at_k is not None and self.hit_at_k < 1.0


@dataclass(frozen=True, slots=True)
class EvalReport:
    """A whole run."""

    dataset: str
    knowledge_base_id: str
    model: str
    cases: list[CaseOutcome]
    started_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())
    finished_at: str = field(default_factory=lambda: datetime.now(UTC).isoformat())

    # ------------------------------------------------------------ counts --

    @property
    def total(self) -> int:
        return len(self.cases)

    @property
    def successful(self) -> int:
        return sum(1 for case in self.cases if case.ok)

    @property
    def failed(self) -> int:
        return sum(1 for case in self.cases if not case.ok)

    # ----------------------------------------------------------- metrics --

    @property
    def metrics(self) -> dict[str, float | None]:
        """Averages over the cases that both ran and stated an expectation.

        Null where no case measured it — reporting 0.0 for a metric nothing
        asked about would read as a failing score.
        """
        ran = [case for case in self.cases if case.ok]

        return {
            "hit_at_k": _mean([case.hit_at_k for case in ran]),
            "mrr": _mean([case.mrr for case in ran]),
            "source_recall": _mean([case.source_recall for case in ran]),
            "source_precision": _mean([case.source_precision for case in ran]),
            "answer_token_f1": _mean([case.answer_token_f1 for case in ran]),
            # Of the questions the bot chose to answer, how many were actually
            # backed by a document.
            #
            # Deliberately *not* the pipeline's `retrieval_grounded` flag. That
            # flag says retrieval cleared its threshold, which stays true even
            # when the model then declines and cites nothing — so reporting it
            # showed 100% groundedness on a run where a third of the answers
            # cited no source at all. Citations are the observable evidence
            # that an answer rests on the corpus, so citations are what this
            # measures.
            "groundedness": _mean(
                [1.0 if case.cited_sources else 0.0 for case in ran if not case.abstained]
            ),
            # How often it answered rather than declining. Reported beside
            # groundedness because the two trade off: a bot that declines
            # everything scores a perfect groundedness and helps nobody.
            "answer_rate": _mean([0.0 if case.abstained else 1.0 for case in ran]),
            "abstention_accuracy": _mean(
                [
                    1.0 if case.abstention_correct else 0.0
                    for case in ran
                    if case.abstention_correct is not None
                ]
            ),
        }

    @property
    def latency(self) -> dict[str, float]:
        values = [case.latency_ms for case in self.cases if case.ok]
        if not values:
            return {"mean_ms": 0.0, "p50_ms": 0.0, "p95_ms": 0.0, "max_ms": 0.0}

        ordered = sorted(values)
        return {
            "mean_ms": round(sum(ordered) / len(ordered), 2),
            "p50_ms": round(_percentile(ordered, 0.50), 2),
            "p95_ms": round(_percentile(ordered, 0.95), 2),
            "max_ms": round(ordered[-1], 2),
        }

    # ------------------------------------------------------------ output --

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "dataset": self.dataset,
            "knowledge_base_id": self.knowledge_base_id,
            "model": self.model,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "totals": {
                "total": self.total,
                "successful": self.successful,
                "failed": self.failed,
                "unmet_expectations": sum(1 for case in self.cases if case.failed_expectation),
            },
            "metrics": self.metrics,
            "latency": self.latency,
            "cases": [asdict(case) for case in self.cases],
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)

    def render(self) -> str:
        """A terminal summary. The JSON file is the record; this is for a human."""
        lines = [
            "",
            "IntelliAssist RAG evaluation",
            "=" * 52,
            f"  dataset            {self.dataset}",
            f"  model              {self.model}",
            f"  questions          {self.total}  ({self.successful} ran, {self.failed} errored)",
            "",
        ]

        for name, value in self.metrics.items():
            shown = "  n/a" if value is None else f"{value:>5.3f}"
            lines.append(f"  {name:<20} {shown}")

        lines.append("")
        for name, value in self.latency.items():
            lines.append(f"  {name:<20} {value:>8.1f}")
        lines.append("=" * 52)

        unmet = [case for case in self.cases if case.failed_expectation]
        if unmet:
            lines.append("\nDid not meet expectations:")
            for case in unmet:
                lines.append(f"  [{case.id}] {case.question}")
                if case.expected_sources:
                    lines.append(
                        f"        expected {case.expected_sources} — cited {case.cited_sources}"
                    )

        errored = [case for case in self.cases if not case.ok]
        if errored:
            lines.append("\nErrored:")
            lines.extend(f"  [{case.id}] {case.question} — {case.error}" for case in errored)

        return "\n".join(lines)


def _mean(values: list[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    if not present:
        return None
    return round(sum(present) / len(present), 4)


def _percentile(ordered: list[float], fraction: float) -> float:
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, round(fraction * (len(ordered) - 1)))
    return ordered[index]
