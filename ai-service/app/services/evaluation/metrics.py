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
import re
import uuid
from collections import Counter
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


# --------------------------------------------------------------- by source --
#
# The chunk-id metrics above are the precise ones, but they need chunk ids,
# which nobody can write by hand. These take the same measurements over
# document filenames, which is what an evaluation dataset can actually express.


def _normalise_source(name: str) -> str:
    """A filename, comparable across paths and cases.

    Only the basename matters: the dataset says "HR Policy.pdf" and the store
    may hold "uploads/2026/HR Policy.pdf".
    """
    return name.replace("\\", "/").rsplit("/", 1)[-1].strip().casefold()


def source_hit_at_k(retrieved: Sequence[str], expected: Sequence[str], k: int) -> float:
    """1.0 if any expected document appears in the first k citations.

    Answers "did retrieval find the right document at all", which is the
    question that matters first — no amount of ranking rescues a miss.
    """
    if not expected:
        return 0.0
    wanted = {_normalise_source(name) for name in expected}
    return float(any(_normalise_source(name) in wanted for name in retrieved[:k]))


def source_reciprocal_rank(retrieved: Sequence[str], expected: Sequence[str]) -> float:
    """1/rank of the first expected document, 0 if absent.

    Rewards putting the right document first rather than merely somewhere:
    rank 1 scores 1.0, rank 2 scores 0.5, rank 5 scores 0.2.
    """
    if not expected:
        return 0.0
    wanted = {_normalise_source(name) for name in expected}
    for index, name in enumerate(retrieved, start=1):
        if _normalise_source(name) in wanted:
            return 1.0 / index
    return 0.0


def source_recall(retrieved: Sequence[str], expected: Sequence[str]) -> float:
    """Fraction of the expected documents that were cited anywhere.

    Distinct from hit@k: a question whose answer spans two documents is only
    half answered if the citations name one of them.
    """
    if not expected:
        return 0.0
    wanted = {_normalise_source(name) for name in expected}
    found = {_normalise_source(name) for name in retrieved} & wanted
    return len(found) / len(wanted)


def source_precision(retrieved: Sequence[str], expected: Sequence[str]) -> float:
    """Of the documents cited, the fraction that were expected.

    Low precision means the answer is padded with sources that did not
    contribute — which makes citations less trustworthy to a reader.
    """
    if not retrieved:
        return 0.0
    if not expected:
        return 0.0
    wanted = {_normalise_source(name) for name in expected}
    hits = sum(1 for name in retrieved if _normalise_source(name) in wanted)
    return hits / len(retrieved)


# -------------------------------------------------------------- by content --

_TOKEN = re.compile(r"[a-z0-9]+")

#: Words carrying no topical signal. Deliberately short — an aggressive list
#: would start removing domain terms, and a wrong answer that happens to share
#: filler words is exactly what this metric must not reward.
_STOPWORDS = frozenset(
    [
        "a",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "by",
        "for",
        "from",
        "has",
        "have",
        "how",
        "in",
        "is",
        "it",
        "its",
        "of",
        "on",
        "or",
        "that",
        "the",
        "to",
        "was",
        "were",
        "what",
        "when",
        "where",
        "which",
        "who",
        "will",
        "with",
        "you",
        "your",
    ]
)


def _content_tokens(text: str) -> list[str]:
    return [token for token in _TOKEN.findall(text.casefold()) if token not in _STOPWORDS]


def answer_token_f1(answer: str, reference: str) -> float:
    """Token-overlap F1 between the answer and a reference answer.

    This is the SQuAD-style metric, and it is **lexical, not semantic**. It
    measures how many content words the two answers share, with multiplicity.

    What that means in practice:

    * "25 days of annual leave" against "annual leave is 25 days" scores high —
      correctly.
    * "Employees get 25 days" against "Staff receive twenty-five days" scores
      poorly despite being right, because it shares almost no tokens.
    * A fluent answer that reuses the reference's vocabulary while stating the
      opposite can still score well.

    So it is a **regression signal**, not a correctness oracle: a sharp drop
    between runs is worth investigating, and an absolute value is not worth
    quoting to anyone as accuracy. Nothing here understands meaning.
    """
    predicted = _content_tokens(answer)
    expected = _content_tokens(reference)

    if not predicted or not expected:
        # Both empty is vacuously perfect; one empty is a total miss.
        return float(not predicted and not expected)

    overlap = Counter(predicted) & Counter(expected)
    shared = sum(overlap.values())
    if shared == 0:
        return 0.0

    precision = shared / len(predicted)
    recall = shared / len(expected)
    return 2 * precision * recall / (precision + recall)
