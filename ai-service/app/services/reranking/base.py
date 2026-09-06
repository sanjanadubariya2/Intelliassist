"""Reranker contract."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol, runtime_checkable

from app.schemas.retrieval import ScoredChunk


@runtime_checkable
class Reranker(Protocol):
    @property
    def name(self) -> str: ...

    @property
    def is_ready(self) -> bool:
        """False while a model is still loading. Gates readiness."""
        ...

    async def rerank(
        self, *, query: str, candidates: Sequence[ScoredChunk], limit: int
    ) -> list[ScoredChunk]:
        """Reorder candidates by true relevance, returning the best ``limit``."""
        ...
