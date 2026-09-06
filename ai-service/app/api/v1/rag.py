"""RAG query endpoints."""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import (
    EmbeddingDep,
    LLMDep,
    RedisDep,
    RerankerDep,
    SessionDep,
    SettingsDep,
)
from app.core.config import Settings
from app.core.exceptions import NotFoundError
from app.core.logging import get_logger
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.schemas.rag import RagQueryRequest, RagQueryResponse
from app.services.embeddings.base import EmbeddingProvider
from app.services.llm.base import LLMProvider
from app.services.query_rewrite import ConversationTurn, LLMQueryRewriter
from app.services.rag_service import RagService
from app.services.reranking.base import Reranker
from app.services.retrieval.bm25 import Bm25Retriever
from app.services.retrieval.dense import DenseRetriever
from app.services.retrieval.fusion import FusionRetriever
from app.services.retrieval.index.bm25_index import Bm25IndexCache

logger = get_logger(__name__)
router = APIRouter(prefix="/rag", tags=["rag"])


def _build_service(
    *,
    settings: Settings,
    session: AsyncSession,
    redis: Redis,
    embeddings: EmbeddingProvider,
    reranker: Reranker,
    llm: LLMProvider,
) -> RagService:
    """Assemble the pipeline for one request.

    Built per request because the retrievers hold a database session, which is
    request-scoped. The expensive pieces — the embedding client and the loaded
    reranker model — are process-wide and injected.
    """
    chunk_repo = ChunkRepository(session)
    config = settings.retrieval

    dense = DenseRetriever(
        chunk_repo=chunk_repo,
        embeddings=embeddings,
        ef_search=config.hnsw_ef_search,
    )
    sparse = Bm25Retriever(
        chunk_repo=chunk_repo,
        cache=Bm25IndexCache(redis, ttl_seconds=config.bm25_cache_ttl_seconds),
    )
    fusion = FusionRetriever(
        retrievers=[dense, sparse],
        weights=[config.dense_weight, config.bm25_weight],
        k=config.rrf_k,
    )
    # Reuses the same provider as generation: one client, one set of
    # retry and timeout rules, one place to change the model.
    rewriter = LLMQueryRewriter(llm=llm, config=settings.query_rewrite)

    return RagService(
        settings=settings,
        retriever=fusion,
        reranker=reranker,
        llm=llm,
        query_rewriter=rewriter,
    )


async def _assert_knowledge_base_exists(
    session: AsyncSession, knowledge_base_id: uuid.UUID
) -> None:
    if await KnowledgeBaseRepository(session).get(knowledge_base_id) is None:
        raise NotFoundError(
            f"Knowledge base {knowledge_base_id} does not exist.",
            details={"knowledge_base_id": str(knowledge_base_id)},
        )


def _history(payload: RagQueryRequest) -> list[ConversationTurn] | None:
    """Adapts the wire payload into the rewriter's own type.

    Kept separate so the transport schema and the domain type can move
    independently — the rewriter has no reason to import a Pydantic model.
    """
    if not payload.history:
        return None
    return [ConversationTurn(role=turn.role, content=turn.content) for turn in payload.history]


@router.post(
    "/query",
    response_model=RagQueryResponse,
    summary="Answer a question from a knowledge base",
)
async def query(
    payload: RagQueryRequest,
    session: SessionDep,
    settings: SettingsDep,
    redis: RedisDep,
    embeddings: EmbeddingDep,
    reranker: RerankerDep,
    llm: LLMDep,
) -> RagQueryResponse:
    """Retrieve, rerank, ground and answer, with inline citations.

    When retrieval finds nothing above the relevance threshold the response has
    `grounded: false`, a fixed refusal, and no citations — the model is not
    called at all.
    """
    await _assert_knowledge_base_exists(session, payload.knowledge_base_id)

    service = _build_service(
        settings=settings,
        session=session,
        redis=redis,
        embeddings=embeddings,
        reranker=reranker,
        llm=llm,
    )
    response = await service.answer(
        query=payload.query,
        knowledge_base_id=payload.knowledge_base_id,
        top_n=payload.top_n,
        document_ids=payload.document_ids,
        include_sources=payload.include_sources,
        history=_history(payload),
    )

    logger.info(
        "query_answered",
        query_id=str(response.query_id),
        grounded=response.grounded,
        rewritten=response.rewritten_query is not None,
        citations=len(response.citations),
        total_ms=response.timings.total_ms,
    )
    return response


@router.post(
    "/stream",
    summary="Answer a question, streamed over SSE",
    response_class=StreamingResponse,
    responses={
        200: {
            "content": {"text/event-stream": {}},
            "description": (
                "Server-Sent Events: `metadata` (with the sources up front), then "
                "many `token`, then `citations`, then `done`. Errors arrive as `error`."
            ),
        }
    },
)
async def stream(
    payload: RagQueryRequest,
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    redis: RedisDep,
    embeddings: EmbeddingDep,
    reranker: RerankerDep,
    llm: LLMDep,
) -> StreamingResponse:
    await _assert_knowledge_base_exists(session, payload.knowledge_base_id)

    service = _build_service(
        settings=settings,
        session=session,
        redis=redis,
        embeddings=embeddings,
        reranker=reranker,
        llm=llm,
    )

    async def events() -> AsyncIterator[str]:
        try:
            async for event_type, data in service.stream(
                query=payload.query,
                knowledge_base_id=payload.knowledge_base_id,
                top_n=payload.top_n,
                document_ids=payload.document_ids,
                history=_history(payload),
            ):
                # Stop generating when the client has gone; otherwise we keep
                # paying the model for tokens nobody will read.
                if await request.is_disconnected():
                    logger.info("stream_client_disconnected")
                    return
                yield _sse(event_type, data)
        except Exception as exc:
            logger.exception("stream_failed", error_type=type(exc).__name__)
            yield _sse("error", {"code": "stream_failed", "message": "Generation failed."})

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # Nginx buffers responses by default, which defeats streaming.
            "X-Accel-Buffering": "no",
        },
    )


def _sse(event: str, data: dict[str, object]) -> str:
    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"
