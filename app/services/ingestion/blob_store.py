"""Temporary storage for uploaded bytes.

The API receives a file; a worker in another process ingests it. Something has
to carry the bytes across, and Redis already spans both. Entries expire, because
this is a hand-off buffer and not a document store — once chunks are indexed the
original bytes are no longer needed, and keeping them would make Redis grow
without bound.
"""

from __future__ import annotations

import uuid

from redis.asyncio import Redis

from app.core.exceptions import NotFoundError

_KEY_PREFIX = "upload:v1"


class UploadBlobStore:
    def __init__(self, redis: Redis, *, ttl_seconds: int = 3600) -> None:
        self._redis = redis
        self._ttl = ttl_seconds

    @staticmethod
    def _key(document_id: uuid.UUID) -> str:
        return f"{_KEY_PREFIX}:{document_id}"

    async def put(self, document_id: uuid.UUID, data: bytes) -> None:
        await self._redis.set(self._key(document_id), data, ex=self._ttl)

    async def get(self, document_id: uuid.UUID) -> bytes:
        data = await self._redis.get(self._key(document_id))
        if data is None:
            raise NotFoundError(
                "Uploaded bytes are no longer available; the upload buffer expired. "
                "Re-upload the document.",
                details={"document_id": str(document_id)},
            )
        return bytes(data)

    async def delete(self, document_id: uuid.UUID) -> None:
        await self._redis.delete(self._key(document_id))
