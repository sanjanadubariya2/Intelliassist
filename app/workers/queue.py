"""Job queue.

arq rather than Celery: asyncio-native, small enough to read in an afternoon,
and it reuses the Redis already in the stack. The protocol exists so tests can
substitute a queue that records instead of enqueues.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Protocol, runtime_checkable

from arq import create_pool
from arq.connections import ArqRedis, RedisSettings

from app.core.config import Settings
from app.core.logging import get_logger

logger = get_logger(__name__)

INGEST_DOCUMENT = "ingest_document"


@runtime_checkable
class JobQueue(Protocol):
    async def enqueue_ingestion(self, document_id: uuid.UUID) -> str | None: ...


class ArqJobQueue:
    """Connects on first enqueue, not at startup.

    Creating the pool eagerly would mean the API refuses to boot whenever Redis
    is briefly unavailable — even though readiness already reports that
    accurately and every other endpoint would work fine.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._pool: ArqRedis | None = None
        self._lock = asyncio.Lock()

    async def _get_pool(self) -> ArqRedis:
        if self._pool is None:
            async with self._lock:
                if self._pool is None:
                    self._pool = await create_pool(RedisSettings.from_dsn(self._settings.redis.url))
        return self._pool

    async def enqueue_ingestion(self, document_id: uuid.UUID) -> str | None:
        pool = await self._get_pool()
        # The document id doubles as the job id, so a duplicate enqueue for the
        # same document is dropped by arq rather than ingesting the file twice.
        job = await pool.enqueue_job(
            INGEST_DOCUMENT, str(document_id), _job_id=f"ingest:{document_id}"
        )
        return job.job_id if job else None

    async def aclose(self) -> None:
        if self._pool is not None:
            await self._pool.aclose()
            self._pool = None


class RecordingJobQueue:
    """Test double: records what would have been enqueued."""

    def __init__(self) -> None:
        self.enqueued: list[uuid.UUID] = []

    async def enqueue_ingestion(self, document_id: uuid.UUID) -> str | None:
        self.enqueued.append(document_id)
        return f"recorded:{document_id}"


def worker_redis_settings(settings: Settings) -> RedisSettings:
    return RedisSettings.from_dsn(settings.redis.url)


# The worker entrypoint is defined in app.workers.ingestion_worker; importing it
# here would make this module import the whole service just to enqueue a job.
def build_worker_settings() -> type[Any]:
    from app.workers.ingestion_worker import WorkerSettings

    return WorkerSettings
