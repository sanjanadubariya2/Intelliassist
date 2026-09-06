"""Evaluation harness.

Runs a golden question set through the real pipeline and reports scores that can
be compared across commits. The point is regression detection: a prompt tweak or
a chunking change that quietly costs recall shows up here as a number, not as a
vague sense that answers got worse.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Sequence
from dataclasses import asdict, dataclass, field
from pathlib import Path

from app.core.logging import get_logger
from app.services.evaluation.metrics import (
    answer_contains_expected,
    citation_precision,
    is_abstention,
    mean,
    score_retrieval,
)
from app.services.rag_service import RagService

logger = get_logger(__name__)


@dataclass(frozen=True, slots=True)
class GoldenCase:
    question: str
    relevant_chunk_ids: set[uuid.UUID] = field(default_factory=set)
    expected_phrases: list[str] = field(default_factory=list)
    should_abstain: bool = False


@dataclass(frozen=True, slots=True)
class CaseResult:
    question: str
    answer: str
    grounded: bool
    abstained: bool
    correct_abstention: bool
    hit_at_5: float
    recall_at_5: float
    precision_at_5: float
    mrr: float
    ndcg_at_5: float
    citation_precision: float
    phrase_coverage: float
    total_ms: float


@dataclass(frozen=True, slots=True)
class EvaluationReport:
    cases: list[CaseResult]

    @property
    def summary(self) -> dict[str, float]:
        answered = [case for case in self.cases if not case.abstained]
        return {
            "cases": float(len(self.cases)),
            "hit@5": mean([case.hit_at_5 for case in answered]),
            "recall@5": mean([case.recall_at_5 for case in answered]),
            "precision@5": mean([case.precision_at_5 for case in answered]),
            "mrr": mean([case.mrr for case in answered]),
            "ndcg@5": mean([case.ndcg_at_5 for case in answered]),
            "citation_precision": mean([case.citation_precision for case in answered]),
            "phrase_coverage": mean([case.phrase_coverage for case in answered]),
            # Whether abstention fired when it should and stayed quiet when it
            # should not. A pipeline that abstains on everything scores
            # perfectly on groundedness and is useless.
            "abstention_accuracy": mean(
                [1.0 if case.correct_abstention else 0.0 for case in self.cases]
            ),
            "p50_ms": _percentile([case.total_ms for case in self.cases], 0.5),
            "p95_ms": _percentile([case.total_ms for case in self.cases], 0.95),
        }

    def to_json(self) -> str:
        return json.dumps(
            {"summary": self.summary, "cases": [asdict(case) for case in self.cases]},
            indent=2,
        )

    def render(self) -> str:
        lines = ["", "IntelliAssist evaluation", "=" * 46]
        for name, value in self.summary.items():
            lines.append(f"  {name:<22} {value:>8.3f}")
        lines.append("=" * 46)

        failures = [case for case in self.cases if not case.correct_abstention]
        if failures:
            lines.append("\nAbstention mismatches:")
            lines.extend(f"  - {case.question}" for case in failures)
        return "\n".join(lines)


class EvaluationRunner:
    def __init__(self, service: RagService) -> None:
        self._service = service

    async def run(
        self, cases: Sequence[GoldenCase], *, knowledge_base_id: uuid.UUID
    ) -> EvaluationReport:
        results = []
        for case in cases:
            response = await self._service.answer(
                query=case.question, knowledge_base_id=knowledge_base_id
            )

            retrieved = [citation.chunk_id for citation in response.citations]
            abstained = is_abstention(response.answer) or not response.grounded
            scores = score_retrieval(retrieved, case.relevant_chunk_ids)

            results.append(
                CaseResult(
                    question=case.question,
                    answer=response.answer,
                    grounded=response.grounded,
                    abstained=abstained,
                    correct_abstention=abstained == case.should_abstain,
                    hit_at_5=scores.hit_at_5,
                    recall_at_5=scores.recall_at_5,
                    precision_at_5=scores.precision_at_5,
                    mrr=scores.mrr,
                    ndcg_at_5=scores.ndcg_at_5,
                    citation_precision=citation_precision(retrieved, case.relevant_chunk_ids),
                    phrase_coverage=answer_contains_expected(
                        response.answer, case.expected_phrases
                    ),
                    total_ms=response.timings.total_ms,
                )
            )

        return EvaluationReport(cases=results)


def load_golden_set(path: Path) -> list[GoldenCase]:
    """Load cases from JSON.

    Shape: ``[{"question": ..., "relevant_chunk_ids": [...],
    "expected_phrases": [...], "should_abstain": false}]``
    """
    payload = json.loads(path.read_text(encoding="utf-8"))
    return [
        GoldenCase(
            question=entry["question"],
            relevant_chunk_ids={uuid.UUID(value) for value in entry.get("relevant_chunk_ids", [])},
            expected_phrases=list(entry.get("expected_phrases", [])),
            should_abstain=bool(entry.get("should_abstain", False)),
        )
        for entry in payload
    ]


def _percentile(values: Sequence[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, round(fraction * (len(ordered) - 1)))
    return ordered[index]
