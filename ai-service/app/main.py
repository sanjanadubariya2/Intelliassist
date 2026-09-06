"""Application factory and lifespan.

Long-lived resources — the database engine, the Redis pool, the embedding
client, the job queue and the cross-encoder — are created once here and attached
to ``app.state``. Nothing in the codebase reaches for a module-level connection.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.errors import register_exception_handlers
from app.api.middleware import RequestContextMiddleware
from app.api.v1.router import api_router, public_router
from app.core.config import Settings, get_settings
from app.core.logging import configure_logging, get_logger
from app.db.redis import create_redis_client, create_redis_pool
from app.db.session import create_engine, create_session_factory
from app.services.ingestion.blob_store import UploadBlobStore
from app.services.providers import build_embedding_provider, build_llm_provider
from app.services.reranking.base import Reranker
from app.services.reranking.noop import NoopReranker
from app.workers.queue import ArqJobQueue

logger = get_logger(__name__)

DESCRIPTION = """
Retrieval-augmented generation over isolated company knowledge bases.

Ingestion is asynchronous: `POST /api/v1/knowledge/documents` returns `202` with
a document id, and `GET /api/v1/knowledge/documents/{id}` reports when that
document became queryable.

Answers are grounded in retrieved sources and cite them inline as `[S1]`. When
retrieval finds nothing relevant the service returns `grounded: false` and a
fixed refusal **without calling the model**.

All endpoints under `/api/v1` require the `X-API-Key` header.
"""


async def _build_reranker(settings: Settings) -> Reranker:
    """Load the cross-encoder, or fall back to passthrough.

    Loading happens at startup rather than on first request, so no user pays
    the several-second model load. sentence-transformers is an optional extra;
    if it is not installed we degrade rather than refuse to boot.
    """
    if not settings.reranker.enabled:
        logger.info("reranker_disabled")
        return NoopReranker()

    from app.services.reranking.cross_encoder import CrossEncoderReranker

    reranker = CrossEncoderReranker(settings.reranker)
    try:
        await reranker.load()
    except Exception as exc:
        logger.warning(
            "reranker_unavailable_falling_back",
            error=str(exc),
            hint="install the 'rerank' extra for cross-encoder reranking",
        )
        return NoopReranker()
    return reranker


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings

    engine = create_engine(settings.database)
    redis_pool = create_redis_pool(settings.redis)
    redis = create_redis_client(redis_pool)

    app.state.engine = engine
    app.state.session_factory = create_session_factory(engine)
    app.state.redis_pool = redis_pool
    app.state.redis = redis
    app.state.blobs = UploadBlobStore(redis, ttl_seconds=settings.ingestion.job_timeout_seconds * 2)
    app.state.embeddings = build_embedding_provider(settings, redis=redis)
    app.state.llm = build_llm_provider(settings)
    app.state.reranker = await _build_reranker(settings)

    app.state.job_queue = ArqJobQueue(settings)

    logger.info(
        "service_started",
        environment=settings.app.env,
        embedding_model=settings.embedding.model,
        embedding_dimension=settings.embedding.dimension,
        llm_model=settings.llm.model,
        reranker=app.state.reranker.name,
        api_key_enforced=settings.app.api_key is not None,
    )

    try:
        yield
    finally:
        await app.state.job_queue.aclose()
        await redis.aclose()
        await redis_pool.disconnect()
        await engine.dispose()
        logger.info("service_stopped")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(level=settings.app.log_level, json_output=settings.app.log_json)

    app = FastAPI(
        title="IntelliAssist RAG Service",
        description=DESCRIPTION,
        version=settings.app.version,
        lifespan=lifespan,
        docs_url="/docs" if settings.is_local else None,
        redoc_url=None,
        openapi_url="/openapi.json",
    )
    app.state.settings = settings

    app.add_middleware(RequestContextMiddleware)
    if settings.app.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.app.cors_origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "DELETE"],
            allow_headers=["*"],
        )

    register_exception_handlers(app)
    app.include_router(public_router)
    app.include_router(api_router)
    return app


app = create_app()
