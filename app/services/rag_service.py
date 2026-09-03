"""RAG orchestration.

Wires the stages together and owns nothing else: no SQL, no prompts, no scoring.
Each stage is injected, so this class is testable with fakes and each stage is
testable without this class.

The order — retrieve, fuse, rerank, budget, ground, generate, validate — is the
pipeline, and the abstain branch sits deliberately before generation.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass

from app.core.config import Settings
from app.core.logging import get_logger
from app.schemas.rag import Citation, QueryTimings, QueryUsage, RagQueryResponse
from app.schemas.retrieval import ScoredChunk
from app.services.citations.context_builder import BuiltContext, ContextBuilder
from app.services.citations.extractor import extract_citations, strip_invalid_markers
from app.services.llm.base import LLMProvider
from app.services.llm.prompts import (
    NO_CONTEXT_ANSWER,
    SYSTEM_PROMPT,
    build_user_prompt,
)
from app.services.reranking.base import Reranker
from app.services.retrieval.base import Retriever

logger = get_logger(__name__)


@dataclass(slots=True)
class _Prepared:
    """Everything decided before the model is called."""

    context: BuiltContext
    candidates: list[ScoredChunk]
    retrieval_ms: float
    rerank_ms: float
    grounded: bool


class RagService:
    def __init__(
        self,
        *,
        settings: Settings,
        retriever: Retriever,
        reranker: Reranker,
        llm: LLMProvider,
    ) -> None:
        self._settings = settings
        self._retriever = retriever
        self._reranker = reranker
        self._llm = llm
        self._context_builder = ContextBuilder(token_budget=settings.retrieval.context_token_budget)

    # ------------------------------------------------------------ retrieval --

    async def _prepare(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        top_n: int | None,
        document_ids: Sequence[uuid.UUID] | None,
    ) -> _Prepared:
        config = self._settings.retrieval
        final_n = top_n or config.final_top_n

        started = time.perf_counter()
        fused = await self._retriever.retrieve(
            query=query,
            knowledge_base_id=knowledge_base_id,
            limit=config.rerank_candidates,
            document_ids=document_ids,
        )
        retrieval_ms = (time.perf_counter() - started) * 1000

        if not fused:
            return _Prepared(
                context=BuiltContext(text="", sources=[], token_count=0),
                candidates=[],
                retrieval_ms=retrieval_ms,
                rerank_ms=0.0,
                grounded=False,
            )

        started = time.perf_counter()
        reranked = await self._reranker.rerank(query=query, candidates=fused, limit=final_n)
        rerank_ms = (time.perf_counter() - started) * 1000

        # The abstain decision. Only meaningful when a real reranker produced
        # calibrated scores — the passthrough reranker carries fusion scores,
        # which are on a different scale entirely and must not be thresholded.
        grounded = bool(reranked)
        if reranked and self._reranker.name != "noop":
            grounded = reranked[0].score >= config.min_rerank_score

        context = self._context_builder.build(reranked) if grounded else BuiltContext("", [], 0)
        return _Prepared(
            context=context,
            candidates=reranked,
            retrieval_ms=retrieval_ms,
            rerank_ms=rerank_ms,
            grounded=grounded and not context.is_empty,
        )

    # --------------------------------------------------------------- answer --

    async def answer(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        top_n: int | None = None,
        document_ids: Sequence[uuid.UUID] | None = None,
        include_sources: bool = True,
    ) -> RagQueryResponse:
        query_id = uuid.uuid4()
        total_started = time.perf_counter()

        prepared = await self._prepare(
            query=query,
            knowledge_base_id=knowledge_base_id,
            top_n=top_n,
            document_ids=document_ids,
        )

        if not prepared.grounded:
            # No LLM call at all. Asking a model to decline politely is a hope;
            # not sending the request is a guarantee — and it costs nothing.
            logger.info(
                "query_abstained",
                query_id=str(query_id),
                knowledge_base_id=str(knowledge_base_id),
                candidates=len(prepared.candidates),
            )
            return self._abstention(
                query_id=query_id,
                knowledge_base_id=knowledge_base_id,
                query=query,
                prepared=prepared,
                total_started=total_started,
            )

        started = time.perf_counter()
        completion = await self._llm.complete(
            system=SYSTEM_PROMPT,
            prompt=build_user_prompt(question=query, context=prepared.context.text),
        )
        generation_ms = (time.perf_counter() - started) * 1000

        citations, invalid = extract_citations(completion.text, prepared.context.sources)
        answer_text = completion.text.strip()
        if invalid:
            logger.warning(
                "fabricated_citation_markers_removed",
                query_id=str(query_id),
                markers=invalid,
                supplied=len(prepared.context.sources),
            )
            answer_text = strip_invalid_markers(
                answer_text, {source.marker for source in prepared.context.sources}
            )

        return RagQueryResponse(
            query_id=query_id,
            knowledge_base_id=knowledge_base_id,
            query=query,
            answer=answer_text,
            citations=citations if include_sources else [],
            grounded=True,
            model=self._llm.model,
            truncated=completion.truncated,
            usage=QueryUsage(
                prompt_tokens=completion.usage.prompt_tokens,
                completion_tokens=completion.usage.completion_tokens,
                context_tokens=prepared.context.token_count,
                candidates_retrieved=len(prepared.candidates),
                sources_used=len(citations),
            ),
            timings=QueryTimings(
                retrieval_ms=round(prepared.retrieval_ms, 2),
                rerank_ms=round(prepared.rerank_ms, 2),
                generation_ms=round(generation_ms, 2),
                total_ms=round((time.perf_counter() - total_started) * 1000, 2),
            ),
        )

    # ------------------------------------------------------------ streaming --

    async def stream(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        top_n: int | None = None,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> AsyncIterator[tuple[str, dict[str, object]]]:
        """Yield ``(event_type, payload)`` pairs for the SSE endpoint.

        Sources are emitted before the first token so a UI can render
        provenance while the answer is still arriving.
        """
        query_id = uuid.uuid4()
        total_started = time.perf_counter()

        prepared = await self._prepare(
            query=query,
            knowledge_base_id=knowledge_base_id,
            top_n=top_n,
            document_ids=document_ids,
        )

        yield (
            "metadata",
            {
                "query_id": str(query_id),
                "knowledge_base_id": str(knowledge_base_id),
                "grounded": prepared.grounded,
                "model": self._llm.model,
                "sources": [
                    _source_payload(source.marker, source.scored)
                    for source in prepared.context.sources
                ],
            },
        )

        if not prepared.grounded:
            yield ("token", {"text": NO_CONTEXT_ANSWER})
            yield ("citations", {"citations": []})
            yield (
                "done",
                {
                    "grounded": False,
                    "total_ms": round((time.perf_counter() - total_started) * 1000, 2),
                },
            )
            return

        collected: list[str] = []
        started = time.perf_counter()
        async for delta in self._llm.stream(
            system=SYSTEM_PROMPT,
            prompt=build_user_prompt(question=query, context=prepared.context.text),
        ):
            collected.append(delta)
            yield ("token", {"text": delta})
        generation_ms = (time.perf_counter() - started) * 1000

        answer = "".join(collected)
        citations, invalid = extract_citations(answer, prepared.context.sources)
        if invalid:
            logger.warning(
                "fabricated_citation_markers_in_stream",
                query_id=str(query_id),
                markers=invalid,
            )

        yield (
            "citations",
            {"citations": [citation.model_dump(mode="json") for citation in citations]},
        )
        yield (
            "done",
            {
                "grounded": True,
                "sources_used": len(citations),
                "context_tokens": prepared.context.token_count,
                "retrieval_ms": round(prepared.retrieval_ms, 2),
                "rerank_ms": round(prepared.rerank_ms, 2),
                "generation_ms": round(generation_ms, 2),
                "total_ms": round((time.perf_counter() - total_started) * 1000, 2),
            },
        )

    # --------------------------------------------------------------- helpers --

    def _abstention(
        self,
        *,
        query_id: uuid.UUID,
        knowledge_base_id: uuid.UUID,
        query: str,
        prepared: _Prepared,
        total_started: float,
    ) -> RagQueryResponse:
        return RagQueryResponse(
            query_id=query_id,
            knowledge_base_id=knowledge_base_id,
            query=query,
            answer=NO_CONTEXT_ANSWER,
            citations=[],
            grounded=False,
            model=self._llm.model,
            usage=QueryUsage(
                candidates_retrieved=len(prepared.candidates),
                context_tokens=0,
                sources_used=0,
            ),
            timings=QueryTimings(
                retrieval_ms=round(prepared.retrieval_ms, 2),
                rerank_ms=round(prepared.rerank_ms, 2),
                generation_ms=0.0,
                total_ms=round((time.perf_counter() - total_started) * 1000, 2),
            ),
        )


def _source_payload(marker: int, scored: ScoredChunk) -> dict[str, object]:
    chunk = scored.chunk
    return {
        "marker": marker,
        "chunk_id": str(chunk.chunk_id),
        "document_id": str(chunk.document_id),
        "filename": chunk.filename,
        "page_number": chunk.page_number,
        "score": round(scored.score, 6),
    }


__all__ = ["Citation", "RagService"]
