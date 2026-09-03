"""Redis connection pool.

Redis backs three things in this service: the embedding cache, the per-knowledge-base
BM25 index cache, and the arq job queue. One pool serves all three.
"""

from __future__ import annotations

from redis.asyncio import ConnectionPool, Redis

from app.core.config import RedisConfig


def create_redis_pool(config: RedisConfig) -> ConnectionPool:
    return ConnectionPool.from_url(
        config.url,
        max_connections=config.max_connections,
        decode_responses=False,  # embedding vectors are stored as raw bytes
    )


def create_redis_client(pool: ConnectionPool) -> Redis:
    return Redis(connection_pool=pool)


async def ping(client: Redis) -> None:
    """Raise if Redis is not reachable. Used by the readiness probe."""
    await client.ping()
