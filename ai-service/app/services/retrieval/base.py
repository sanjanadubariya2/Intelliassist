"""Retriever contract.

Every retriever takes the same inputs and returns the same shape, which is what
lets fusion treat them interchangeably and lets each be tested in isolation.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import Protocol, runtime_checkable

from app.schemas.retrieval import ScoredChunk


@runtime_checkable
class Retriever(Protocol):
    async def retrieve(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        limit: int,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> list[ScoredChunk]:
        """Best ``limit`` chunks for the query, highest score first."""
        ...
