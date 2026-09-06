"""Redis-backed embedding cache.

Embedding is the slowest and only metered step in ingestion, and corpora repeat
themselves — boilerplate headers, shared policy paragraphs, re-uploaded
documents. Caching on a hash of (model, dimension, task, text) turns those into
a Redis lookup.

The model and dimension are part of the key on purpose: vectors from different
models are not comparable, so a cache hit across models would be a correctness
bug, not a saving.
"""

from __future__ import annotations

import hashlib
import struct
from collections.abc import Sequence

from redis.asyncio import Redis

from app.core.logging import get_logger
from app.services.embeddings.base import EmbeddingProvider, EmbeddingTask

logger = get_logger(__name__)

_KEY_PREFIX = "emb:v1"


class CachedEmbeddingProvider:
    """Wraps any provider. Satisfies the same protocol, so nothing downstream
    knows the cache exists."""

    def __init__(
        self,
        inner: EmbeddingProvider,
        redis: Redis,
        *,
        ttl_seconds: int,
    ) -> None:
        self._inner = inner
        self._redis = redis
        self._ttl = ttl_seconds
        self.hits = 0
        self.misses = 0

    @property
    def model(self) -> str:
        return self._inner.model

    @property
    def dimension(self) -> int:
        return self._inner.dimension

    async def embed(
        self, texts: Sequence[str], *, task: EmbeddingTask = EmbeddingTask.DOCUMENT
    ) -> list[list[float]]:
        if not texts:
            return []

        keys = [self._key(text, task) for text in texts]
        cached = await self._get_many(keys)

        missing = [index for index, vector in enumerate(cached) if vector is None]
        self.hits += len(texts) - len(missing)
        self.misses += len(missing)

        if missing:
            fresh = await self._inner.embed([texts[index] for index in missing], task=task)
            for index, vector in zip(missing, fresh, strict=True):
                cached[index] = vector
            await self._set_many(
                {keys[index]: vector for index, vector in zip(missing, fresh, strict=True)}
            )

        # Every slot is filled by now; the cast keeps the signature honest.
        return [vector for vector in cached if vector is not None]

    async def embed_one(
        self, text: str, *, task: EmbeddingTask = EmbeddingTask.QUERY
    ) -> list[float]:
        vectors = await self.embed([text], task=task)
        return vectors[0]

    # ------------------------------------------------------------ internals --

    def _key(self, text: str, task: EmbeddingTask) -> str:
        digest = hashlib.sha256(
            f"{self.model}|{self.dimension}|{task.value}|{text}".encode()
        ).hexdigest()
        return f"{_KEY_PREFIX}:{digest}"

    async def _get_many(self, keys: list[str]) -> list[list[float] | None]:
        try:
            raw_values = await self._redis.mget(keys)
        except Exception as exc:
            # A cache outage must not fail ingestion — degrade to always-miss.
            logger.warning("embedding_cache_read_failed", error=str(exc))
            return [None] * len(keys)

        return [self._decode(raw) if raw else None for raw in raw_values]

    async def _set_many(self, entries: dict[str, list[float]]) -> None:
        if not entries:
            return
        try:
            async with self._redis.pipeline(transaction=False) as pipe:
                for key, vector in entries.items():
                    pipe.set(key, self._encode(vector), ex=self._ttl)
                await pipe.execute()
        except Exception as exc:
            logger.warning("embedding_cache_write_failed", error=str(exc))

    @staticmethod
    def _encode(vector: list[float]) -> bytes:
        # Packed float32 rather than JSON: a 768-dim vector is 3 KB instead of
        # roughly 15 KB of text, and decoding is a single memory read.
        return struct.pack(f"<{len(vector)}f", *vector)

    def _decode(self, raw: bytes) -> list[float] | None:
        count = len(raw) // 4
        if count != self.dimension:
            # A stale entry from a different dimension. Treat as a miss.
            return None
        return list(struct.unpack(f"<{count}f", raw))
