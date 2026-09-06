"""Passthrough reranker.

Used when torch is not installed or reranking is switched off. It truncates
rather than reorders, so the pipeline still returns the fusion ordering and the
service stays usable — with measurably worse precision, which is the honest
trade rather than a hidden one.
"""

from __future__ import annotations

from collections.abc import Sequence

from app.schemas.retrieval import ScoredChunk


class NoopReranker:
    @property
    def name(self) -> str:
        return "noop"

    @property
    def is_ready(self) -> bool:
        return True

    async def rerank(
        self, *, query: str, candidates: Sequence[ScoredChunk], limit: int
    ) -> list[ScoredChunk]:
        return list(candidates[:limit])
