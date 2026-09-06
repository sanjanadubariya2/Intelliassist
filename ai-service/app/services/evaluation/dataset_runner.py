"""Runs an evaluation dataset against the RAG pipeline.

Isolation, which is the part that matters:

* It calls `RagService.answer` — the same code path production uses, so the
  numbers describe the real pipeline rather than a reimplementation of it.
* It creates no conversation, stores no message, and records no usage. The
  RAG service does none of that; conversations and analytics live entirely in
  Spring Boot, which is not involved here. Evaluation traffic therefore cannot
  appear in a customer's dashboard.
* It only reads from the knowledge base. Nothing is ingested, updated or
  deleted, so a run cannot alter what production retrieval will find.

The one cost it does incur is model calls, which are real and are billed.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Sequence
from datetime import UTC, datetime

from structlog import get_logger

from app.services.evaluation.dataset import EvalCase
from app.services.evaluation.metrics import (
    answer_token_f1,
    is_abstention,
    source_hit_at_k,
    source_precision,
    source_recall,
    source_reciprocal_rank,
)
from app.services.evaluation.report import CaseOutcome, EvalReport
from app.services.rag_service import RagService

logger = get_logger(__name__)

#: The cutoff for hit@k and the ranking metrics. Five matches the pipeline's
#: default `final_top_n`, so the metric measures what a user would actually be
#: shown rather than a deeper list they never see.
DEFAULT_K = 5


class DatasetEvaluationRunner:
    def __init__(self, service: RagService, *, k: int = DEFAULT_K) -> None:
        self._service = service
        self._k = k

    async def run(
        self,
        cases: Sequence[EvalCase],
        *,
        knowledge_base_id: uuid.UUID,
        dataset: str = "",
    ) -> EvalReport:
        started = datetime.now(UTC).isoformat()
        outcomes: list[CaseOutcome] = []
        # Read from the first answer rather than asked of the service, so the
        # runner needs nothing from RagService that production does not.
        model = ""

        for case in cases:
            outcome, answered_by = await self._run_case(case, knowledge_base_id)
            outcomes.append(outcome)
            model = model or answered_by

        return EvalReport(
            dataset=dataset,
            knowledge_base_id=str(knowledge_base_id),
            model=model,
            cases=outcomes,
            started_at=started,
            finished_at=datetime.now(UTC).isoformat(),
        )

    async def _run_case(
        self, case: EvalCase, knowledge_base_id: uuid.UUID
    ) -> tuple[CaseOutcome, str]:
        """Evaluates one question.

        A failure here is recorded, not raised: one question that times out
        must not discard the results of the fifty that already ran and cost
        money.
        """
        started = time.perf_counter()
        try:
            response = await self._service.answer(
                query=case.question, knowledge_base_id=knowledge_base_id
            )
        except Exception as exc:
            elapsed = (time.perf_counter() - started) * 1000
            logger.warning("eval_case_failed", case_id=case.id, error=type(exc).__name__)
            return CaseOutcome(
                id=case.id,
                question=case.question,
                ok=False,
                # The type and message, not a traceback: this ends up in a
                # JSON file that a dashboard renders.
                error=f"{type(exc).__name__}: {exc}",
                expected_sources=list(case.expected_sources),
                latency_ms=round(elapsed, 2),
            ), ""

        cited = [citation.filename for citation in response.citations]
        abstained = not response.grounded or is_abstention(response.answer)
        expected = case.expected_sources

        return CaseOutcome(
            id=case.id,
            question=case.question,
            ok=True,
            answer=response.answer,
            retrieval_grounded=response.grounded,
            abstained=abstained,
            expected_sources=list(expected),
            cited_sources=cited,
            # Left null when the case stated no expectation, so the averages
            # are over cases that actually asked the question.
            hit_at_k=source_hit_at_k(cited, expected, self._k) if expected else None,
            mrr=source_reciprocal_rank(cited, expected) if expected else None,
            source_recall=source_recall(cited, expected) if expected else None,
            source_precision=source_precision(cited, expected) if expected else None,
            answer_token_f1=(
                answer_token_f1(response.answer, case.reference_answer or "")
                if case.has_reference
                else None
            ),
            abstention_correct=abstained == case.should_abstain,
            latency_ms=response.timings.total_ms,
        ), response.model
