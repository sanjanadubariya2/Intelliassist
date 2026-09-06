"""Embedding provider contract.

A Protocol rather than a base class: a test double is any object with these
methods, so the unit suite needs no inheritance, no registration and no network.
Adding OpenAI later means writing one class that satisfies this — nothing in the
retrieval pipeline changes.
"""

from __future__ import annotations

from collections.abc import Sequence
from enum import StrEnum
from typing import Protocol, runtime_checkable


class EmbeddingTask(StrEnum):
    """Retrieval is asymmetric: a question and a passage are embedded differently.

    Providers that support this produce noticeably better retrieval when told
    which side they are encoding. Providers that do not can ignore it.
    """

    DOCUMENT = "document"
    QUERY = "query"


@runtime_checkable
class EmbeddingProvider(Protocol):
    @property
    def model(self) -> str: ...

    @property
    def dimension(self) -> int: ...

    async def embed(
        self, texts: Sequence[str], *, task: EmbeddingTask = EmbeddingTask.DOCUMENT
    ) -> list[list[float]]:
        """Embed a batch, returning one vector per input, in the same order."""
        ...

    async def embed_one(
        self, text: str, *, task: EmbeddingTask = EmbeddingTask.QUERY
    ) -> list[float]: ...
