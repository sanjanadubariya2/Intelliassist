"""Internal retrieval DTOs.

These never appear in an HTTP response — that is what ``schemas/rag.py`` is for.
They exist so the retrievers, the fuser and the reranker exchange a typed value
rather than ORM objects, which is what lets each stage be unit-tested on plain
fixtures with no database attached.

Frozen dataclasses rather than Pydantic models: these are hot-path objects
passed between stages, and they carry no untrusted input to validate.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field, replace
from enum import StrEnum
from typing import Any


class RetrievalSource(StrEnum):
    DENSE = "dense"
    BM25 = "bm25"
    FUSION = "fusion"
    RERANK = "rerank"


@dataclass(frozen=True, slots=True)
class RetrievedChunk:
    """A chunk plus the document context a citation needs."""

    chunk_id: uuid.UUID
    document_id: uuid.UUID
    knowledge_base_id: uuid.UUID
    chunk_index: int
    text: str
    token_count: int
    filename: str
    page_number: int | None = None
    meta: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class ScoredChunk:
    """A chunk with the score from whichever stage produced it.

    Scores are only comparable within one source: cosine similarity, BM25 and
    cross-encoder logits live on different scales. That is precisely why fusion
    uses ranks rather than raw scores.
    """

    chunk: RetrievedChunk
    score: float
    source: RetrievalSource

    def rescored(self, score: float, source: RetrievalSource) -> ScoredChunk:
        return replace(self, score=score, source=source)

    @property
    def chunk_id(self) -> uuid.UUID:
        return self.chunk.chunk_id
