"""Reciprocal Rank Fusion.

Dense similarity, BM25 and cross-encoder logits live on completely different
scales, so combining their raw scores requires normalisation that is arbitrary
and corpus-dependent. RRF sidesteps that entirely by using only *rank*:

    score(chunk) = sum over retrievers of  weight / (k + rank)

with rank starting at 1. The constant ``k`` (60 by convention) damps the
influence of the very top positions, so one retriever being confidently wrong
does not dominate. A chunk found by both retrievers accumulates from both, which
is exactly the agreement signal hybrid search exists to capture.

The function is pure and synchronous: given ranked lists it needs no database,
no network and no configuration beyond k, which is what makes it directly
testable against hand-computed values.
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Sequence

from app.schemas.retrieval import RetrievalSource, ScoredChunk
from app.services.retrieval.base import Retriever

DEFAULT_RRF_K = 60


def reciprocal_rank_fusion(
    ranked_lists: Sequence[Sequence[ScoredChunk]],
    *,
    k: int = DEFAULT_RRF_K,
    weights: Sequence[float] | None = None,
    limit: int | None = None,
) -> list[ScoredChunk]:
    """Fuse ranked lists into one ordering."""
    if weights is None:
        weights = [1.0] * len(ranked_lists)
    if len(weights) != len(ranked_lists):
        raise ValueError("weights must have one entry per ranked list")

    totals: dict[uuid.UUID, float] = {}
    best: dict[uuid.UUID, ScoredChunk] = {}

    for ranked, weight in zip(ranked_lists, weights, strict=True):
        for position, scored in enumerate(ranked, start=1):
            chunk_id = scored.chunk_id
            totals[chunk_id] = totals.get(chunk_id, 0.0) + weight / (k + position)
            # Keep one representative per chunk; they carry identical text.
            best.setdefault(chunk_id, scored)

    fused = sorted(
        (
            best[chunk_id].rescored(score, RetrievalSource.FUSION)
            for chunk_id, score in totals.items()
        ),
        key=lambda scored: scored.score,
        reverse=True,
    )
    return fused[:limit] if limit is not None else fused


class FusionRetriever:
    """Runs the dense and sparse retrievers concurrently, then fuses them."""

    def __init__(
        self,
        *,
        retrievers: Sequence[Retriever],
        weights: Sequence[float] | None = None,
        k: int = DEFAULT_RRF_K,
    ) -> None:
        if not retrievers:
            raise ValueError("FusionRetriever needs at least one retriever")
        self._retrievers = list(retrievers)
        self._weights = list(weights) if weights else [1.0] * len(retrievers)
        self._k = k

    async def retrieve(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        limit: int,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> list[ScoredChunk]:
        # Concurrently: the two are independent, and dense retrieval waits on an
        # embedding API call that BM25 has no reason to sit behind.
        results = await asyncio.gather(
            *(
                retriever.retrieve(
                    query=query,
                    knowledge_base_id=knowledge_base_id,
                    limit=limit,
                    document_ids=document_ids,
                )
                for retriever in self._retrievers
            ),
            return_exceptions=True,
        )

        ranked_lists: list[Sequence[ScoredChunk]] = []
        weights: list[float] = []
        for retriever, weight, result in zip(self._retrievers, self._weights, results, strict=True):
            if isinstance(result, BaseException):
                # One retriever failing degrades quality; it should not take the
                # whole query down when the other still has answers.
                from app.core.logging import get_logger

                get_logger(__name__).warning(
                    "retriever_failed",
                    retriever=type(retriever).__name__,
                    error=str(result),
                )
                continue
            ranked_lists.append(result)
            weights.append(weight)

        if not ranked_lists:
            return []

        return reciprocal_rank_fusion(ranked_lists, k=self._k, weights=weights, limit=limit)
