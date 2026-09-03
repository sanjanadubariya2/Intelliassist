"""FastAPI dependencies.

Shared resources live on ``app.state``, created once in the lifespan handler.
Dependencies read them from the request rather than importing module globals,
which is what lets tests build an app with substitutes.
"""

from __future__ import annotations

import secrets
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, Request
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.core.config import Settings
from app.core.exceptions import AuthenticationError
from app.db.session import session_scope
from app.services.embeddings.base import EmbeddingProvider
from app.services.ingestion.blob_store import UploadBlobStore
from app.services.llm.base import LLMProvider
from app.services.reranking.base import Reranker
from app.workers.queue import JobQueue

API_KEY_HEADER = "X-API-Key"


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings  # type: ignore[no-any-return]


def get_engine(request: Request) -> AsyncEngine:
    return request.app.state.engine  # type: ignore[no-any-return]


def get_session_factory(request: Request) -> async_sessionmaker[AsyncSession]:
    return request.app.state.session_factory  # type: ignore[no-any-return]


def get_redis(request: Request) -> Redis:
    return request.app.state.redis  # type: ignore[no-any-return]


async def get_session(
    factory: Annotated[async_sessionmaker[AsyncSession], Depends(get_session_factory)],
) -> AsyncIterator[AsyncSession]:
    async with session_scope(factory) as session:
        yield session


async def require_api_key(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings_dep)],
) -> None:
    """Service-to-service guard.

    Not user authentication: there is no identity here, only proof that the
    caller is the service we expect. When no key is configured the guard is
    inert, which is allowed locally and rejected at startup in deployed
    environments by ``Settings`` validation.
    """
    configured = settings.app.api_key
    if configured is None:
        return

    presented = request.headers.get(API_KEY_HEADER)
    if presented is None:
        raise AuthenticationError(f"Missing {API_KEY_HEADER} header.")

    # Constant-time: a plain != leaks key material through timing.
    if not secrets.compare_digest(presented, configured.get_secret_value()):
        raise AuthenticationError("Invalid API key.")


SettingsDep = Annotated[Settings, Depends(get_settings_dep)]
SessionDep = Annotated[AsyncSession, Depends(get_session)]
RedisDep = Annotated[Redis, Depends(get_redis)]
EngineDep = Annotated[AsyncEngine, Depends(get_engine)]


def get_job_queue(request: Request) -> JobQueue:
    return request.app.state.job_queue  # type: ignore[no-any-return]


def get_embedding_provider(request: Request) -> EmbeddingProvider:
    return request.app.state.embeddings  # type: ignore[no-any-return]


def get_llm_provider(request: Request) -> LLMProvider:
    return request.app.state.llm  # type: ignore[no-any-return]


def get_reranker(request: Request) -> Reranker:
    return request.app.state.reranker  # type: ignore[no-any-return]


def get_blob_store(request: Request) -> UploadBlobStore:
    return request.app.state.blobs  # type: ignore[no-any-return]


JobQueueDep = Annotated[JobQueue, Depends(get_job_queue)]
EmbeddingDep = Annotated[EmbeddingProvider, Depends(get_embedding_provider)]
LLMDep = Annotated[LLMProvider, Depends(get_llm_provider)]
RerankerDep = Annotated[Reranker, Depends(get_reranker)]
BlobStoreDep = Annotated[UploadBlobStore, Depends(get_blob_store)]
