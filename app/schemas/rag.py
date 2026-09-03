"""Public RAG contract — what Spring Boot codes against."""

from __future__ import annotations

import uuid
from enum import StrEnum

from pydantic import BaseModel, Field


class Citation(BaseModel):
    marker: int = Field(description="The [S#] number used inline in the answer.")
    chunk_id: uuid.UUID
    document_id: uuid.UUID
    filename: str
    page_number: int | None = None
    snippet: str = Field(description="The supporting passage, truncated for display.")
    score: float = Field(description="Relevance from the final ranking stage.")


class QueryTimings(BaseModel):
    """Per-stage latency, so a slow query can be attributed from the caller."""

    retrieval_ms: float = 0.0
    rerank_ms: float = 0.0
    generation_ms: float = 0.0
    total_ms: float = 0.0


class QueryUsage(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    context_tokens: int = 0
    candidates_retrieved: int = 0
    sources_used: int = 0


class RagQueryRequest(BaseModel):
    knowledge_base_id: uuid.UUID
    query: str = Field(min_length=1, max_length=4000)
    top_n: int | None = Field(
        default=None,
        ge=1,
        le=20,
        description="Sources to put in front of the model. Defaults to configuration.",
    )
    document_ids: list[uuid.UUID] | None = Field(
        default=None,
        description="Restrict retrieval to these documents within the knowledge base.",
    )
    include_sources: bool = Field(
        default=True, description="Set false to omit citation snippets from the response."
    )


class RagQueryResponse(BaseModel):
    query_id: uuid.UUID
    knowledge_base_id: uuid.UUID
    query: str
    answer: str
    citations: list[Citation] = Field(default_factory=list)
    grounded: bool = Field(
        description=(
            "False when retrieval found nothing above the relevance threshold. "
            "The answer is then a fixed refusal and the model was not called."
        )
    )
    model: str
    truncated: bool = Field(
        default=False,
        description=(
            "True when the model hit its output cap. The answer is usable but "
            "incomplete, and may have lost trailing citation markers."
        ),
    )
    usage: QueryUsage
    timings: QueryTimings


class StreamEventType(StrEnum):
    METADATA = "metadata"
    TOKEN = "token"
    CITATIONS = "citations"
    DONE = "done"
    ERROR = "error"
