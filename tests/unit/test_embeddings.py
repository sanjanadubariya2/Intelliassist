"""Embedding abstraction, the deterministic fake, and the cache wrapper."""

from __future__ import annotations

import math

import pytest

from app.services.embeddings.base import EmbeddingProvider, EmbeddingTask
from app.services.embeddings.cache import CachedEmbeddingProvider
from app.services.embeddings.fake import FakeEmbeddingProvider


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=True))


def test_fake_provider_satisfies_the_protocol() -> None:
    # Structural typing is what lets tests substitute without inheritance.
    assert isinstance(FakeEmbeddingProvider(), EmbeddingProvider)


async def test_vectors_have_the_configured_width() -> None:
    vectors = await FakeEmbeddingProvider(dimension=768).embed(["a", "b"])

    assert [len(vector) for vector in vectors] == [768, 768]


async def test_vectors_are_unit_length() -> None:
    # pgvector cosine distance assumes normalised vectors.
    vector = await FakeEmbeddingProvider().embed_one("some text")

    assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1.0)


async def test_same_text_gives_the_same_vector() -> None:
    provider = FakeEmbeddingProvider()

    assert await provider.embed_one("stable") == await provider.embed_one("stable")


async def test_similar_text_is_closer_than_unrelated_text() -> None:
    # This property is what makes the fake usable in retrieval tests rather
    # than merely type-correct.
    provider = FakeEmbeddingProvider()
    query = await provider.embed_one("annual leave vacation policy")
    related = await provider.embed_one("the vacation policy grants annual leave")
    unrelated = await provider.embed_one("printer toner replacement procedure")

    assert cosine(query, related) > cosine(query, unrelated)


async def test_empty_text_still_produces_a_valid_vector() -> None:
    vector = await FakeEmbeddingProvider().embed_one("")

    assert math.sqrt(sum(value * value for value in vector)) == pytest.approx(1.0)


async def test_embedding_an_empty_batch_is_a_no_op() -> None:
    assert await FakeEmbeddingProvider().embed([]) == []


async def test_batch_order_is_preserved() -> None:
    provider = FakeEmbeddingProvider()
    batch = await provider.embed(["one", "two", "three"])

    assert batch[1] == await provider.embed_one("two")


# ---------------------------------------------------------------- cache ----


class _FakeRedis:
    """Minimal async Redis stand-in covering what the cache actually uses."""

    def __init__(self, *, broken: bool = False) -> None:
        self.store: dict[str, bytes] = {}
        self.broken = broken

    async def mget(self, keys: list[str]) -> list[bytes | None]:
        if self.broken:
            raise ConnectionError("redis down")
        return [self.store.get(key) for key in keys]

    def pipeline(self, transaction: bool = True):  # type: ignore[no-untyped-def]
        outer = self

        class _Pipe:
            def __init__(self) -> None:
                self.ops: list[tuple[str, bytes]] = []

            async def __aenter__(self):  # type: ignore[no-untyped-def]
                return self

            async def __aexit__(self, *_: object) -> None:
                return None

            def set(self, key: str, value: bytes, ex: int | None = None) -> None:
                self.ops.append((key, value))

            async def execute(self) -> None:
                if outer.broken:
                    raise ConnectionError("redis down")
                outer.store.update(dict(self.ops))

        return _Pipe()


async def test_second_call_is_served_from_cache() -> None:
    inner = FakeEmbeddingProvider()
    cached = CachedEmbeddingProvider(inner, _FakeRedis(), ttl_seconds=60)  # type: ignore[arg-type]

    await cached.embed(["repeated text"])
    await cached.embed(["repeated text"])

    assert inner.call_count == 1
    assert cached.hits == 1 and cached.misses == 1


async def test_only_the_missing_texts_reach_the_provider() -> None:
    inner = FakeEmbeddingProvider()
    cached = CachedEmbeddingProvider(inner, _FakeRedis(), ttl_seconds=60)  # type: ignore[arg-type]

    await cached.embed(["a"])
    inner.embedded_texts.clear()
    await cached.embed(["a", "b"])

    assert inner.embedded_texts == ["b"]


async def test_cached_vectors_round_trip_accurately() -> None:
    inner = FakeEmbeddingProvider()
    cached = CachedEmbeddingProvider(inner, _FakeRedis(), ttl_seconds=60)  # type: ignore[arg-type]

    first = await cached.embed_one("text")
    second = await cached.embed_one("text")

    # float32 packing, so exact equality is not the bar.
    assert first == pytest.approx(second, abs=1e-6)


async def test_query_and_document_tasks_do_not_share_a_cache_entry() -> None:
    inner = FakeEmbeddingProvider()
    cached = CachedEmbeddingProvider(inner, _FakeRedis(), ttl_seconds=60)  # type: ignore[arg-type]

    await cached.embed(["same"], task=EmbeddingTask.DOCUMENT)
    await cached.embed(["same"], task=EmbeddingTask.QUERY)

    # Asymmetric retrieval encodes the two sides differently; sharing an entry
    # would silently return the wrong side's vector.
    assert inner.call_count == 2


async def test_redis_failure_degrades_to_uncached_rather_than_erroring() -> None:
    inner = FakeEmbeddingProvider()
    cached = CachedEmbeddingProvider(inner, _FakeRedis(broken=True), ttl_seconds=60)  # type: ignore[arg-type]

    vectors = await cached.embed(["text"])

    assert len(vectors) == 1
    assert inner.call_count == 1
