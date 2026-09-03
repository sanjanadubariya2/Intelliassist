"""Retrieval and generation metrics.

Pure functions over ids and strings — no database, no model — so the harness is
testable and a score is reproducible from a saved run.

Retrieval metrics answer "did we put the right chunk in front of the model".
Generation metrics answer "did the model stay inside what we gave it". Both are
needed: perfect retrieval with an ungrounded answer is still a wrong answer, and
a beautifully grounded answer over the wrong sources is still wrong.
"""

from __future__ import annotations

import math
import uuid
from collections.abc import Sequence
from dataclasses import dataclass


def hit_at_k(retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID], k: int) -> float:
    """1.0 if any relevant chunk appears in the top k."""
    if not relevant:
        return 0.0
    return 1.0 if any(chunk_id in relevant for chunk_id in retrieved[:k]) else 0.0


def recall_at_k(retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID], k: int) -> float:
    """Fraction of the relevant chunks that made it into the top k."""
    if not relevant:
        return 0.0
    found = sum(1 for chunk_id in retrieved[:k] if chunk_id in relevant)
    return found / len(relevant)


def precision_at_k(retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID], k: int) -> float:
    window = retrieved[:k]
    if not window:
        return 0.0
    return sum(1 for chunk_id in window if chunk_id in relevant) / len(window)


def reciprocal_rank(retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID]) -> float:
    """1/rank of the first relevant chunk. Rewards putting it first, not just
    somewhere."""
    for position, chunk_id in enumerate(retrieved, start=1):
        if chunk_id in relevant:
            return 1.0 / position
    return 0.0


def ndcg_at_k(retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID], k: int) -> float:
    """Normalised discounted cumulative gain, binary relevance.

    Unlike hit@k it distinguishes rank 1 from rank 5, which is what actually
    matters once the top n are all fed to the model.
    """
    if not relevant:
        return 0.0

    gain = sum(
        1.0 / math.log2(position + 1)
        for position, chunk_id in enumerate(retrieved[:k], start=1)
        if chunk_id in relevant
    )
    ideal = sum(1.0 / math.log2(position + 1) for position in range(1, min(len(relevant), k) + 1))
    return gain / ideal if ideal else 0.0


def citation_precision(cited: Sequence[uuid.UUID], relevant: set[uuid.UUID]) -> float:
    """Of the sources the answer cited, how many were actually relevant."""
    if not cited:
        return 0.0
    return sum(1 for chunk_id in cited if chunk_id in relevant) / len(cited)


def answer_contains_expected(answer: str, expected_phrases: Sequence[str]) -> float:
    """Fraction of expected phrases present, case-insensitively.

    A blunt proxy for correctness. It is honest about being blunt: it catches
    regressions where an answer stops mentioning the key fact, and it says
    nothing about fluency.
    """
    if not expected_phrases:
        return 0.0
    lowered = answer.lower()
    return sum(1 for phrase in expected_phrases if phrase.lower() in lowered) / len(
        expected_phrases
    )


def is_abstention(answer: str) -> bool:
    return "don't have information" in answer.lower()


@dataclass(frozen=True, slots=True)
class RetrievalScores:
    hit_at_5: float
    recall_at_5: float
    precision_at_5: float
    mrr: float
    ndcg_at_5: float


def score_retrieval(
    retrieved: Sequence[uuid.UUID], relevant: set[uuid.UUID], *, k: int = 5
) -> RetrievalScores:
    return RetrievalScores(
        hit_at_5=hit_at_k(retrieved, relevant, k),
        recall_at_5=recall_at_k(retrieved, relevant, k),
        precision_at_5=precision_at_k(retrieved, relevant, k),
        mrr=reciprocal_rank(retrieved, relevant),
        ndcg_at_5=ndcg_at_k(retrieved, relevant, k),
    )


def mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0
