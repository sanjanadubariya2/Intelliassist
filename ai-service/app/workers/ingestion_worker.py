"""arq worker that runs document ingestion.

Run with:  arq app.workers.ingestion_worker.WorkerSettings

Each job owns its own database session. The pipeline commits progress as it
advances through the stages, so a crash leaves the document in the stage it
reached with an error message, rather than stuck at 'pending' forever.
"""

from __future__ import annotations

import uuid
from typing import Any, ClassVar

from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger
from app.db.redis import create_redis_client, create_redis_pool
from app.db.session import create_engine, create_session_factory, session_scope
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.services.ingestion.blob_store import UploadBlobStore
from app.services.ingestion.pipeline import IngestionPipeline
from app.services.providers import build_embedding_provider
from app.workers.queue import INGEST_DOCUMENT, worker_redis_settings

logger = get_logger(__name__)


async def startup(ctx: dict[str, Any]) -> None:
    settings = get_settings()
    configure_logging(level=settings.app.log_level, json_output=settings.app.log_json)

    engine = create_engine(settings.database)
    redis_pool = create_redis_pool(settings.redis)
    redis = create_redis_client(redis_pool)

    ctx["settings"] = settings
    ctx["engine"] = engine
    ctx["session_factory"] = create_session_factory(engine)
    ctx["redis_pool"] = redis_pool
    ctx["redis"] = redis
    ctx["embeddings"] = build_embedding_provider(settings, redis=redis)
    ctx["blobs"] = UploadBlobStore(redis, ttl_seconds=settings.ingestion.job_timeout_seconds * 2)

    logger.info(
        "worker_started",
        embedding_model=ctx["embeddings"].model,
        embedding_dimension=ctx["embeddings"].dimension,
    )


async def shutdown(ctx: dict[str, Any]) -> None:
    await ctx["redis"].aclose()
    await ctx["redis_pool"].disconnect()
    await ctx["engine"].dispose()
    logger.info("worker_stopped")


async def ingest_document(ctx: dict[str, Any], document_id: str) -> dict[str, Any]:
    identifier = uuid.UUID(document_id)
    settings = ctx["settings"]
    blobs: UploadBlobStore = ctx["blobs"]

    data = await blobs.get(identifier)

    async with session_scope(ctx["session_factory"]) as session:
        pipeline = IngestionPipeline(
            settings=settings,
            embeddings=ctx["embeddings"],
            document_repo=DocumentRepository(session),
            chunk_repo=ChunkRepository(session),
            knowledge_base_repo=KnowledgeBaseRepository(session),
        )
        result = await pipeline.ingest(document_id=identifier, data=data)

    # Only once the chunks are committed. Keeping the bytes until then means a
    # retry after a mid-ingestion crash still has something to work from.
    await blobs.delete(identifier)

    return {
        "document_id": str(result.document_id),
        "chunk_count": result.chunk_count,
        "status": result.status.value,
    }


class WorkerSettings:
    functions: ClassVar[list[Any]] = [ingest_document]
    on_startup = startup
    on_shutdown = shutdown
    redis_settings = worker_redis_settings(get_settings())
    job_timeout = get_settings().ingestion.job_timeout_seconds
    max_tries = get_settings().ingestion.max_job_retries + 1
    keep_result = 3600


# arq resolves the job by name; keep the registered name and the constant aligned.
ingest_document.__name__ = INGEST_DOCUMENT
