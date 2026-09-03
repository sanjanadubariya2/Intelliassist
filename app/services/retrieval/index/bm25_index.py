"""Per-knowledge-base BM25 index.

rank-bm25 holds the whole index in process memory, so it is built lazily per
knowledge base and cached in Redis. What gets cached is the tokenised corpus and
its chunk ids, not the BM25 object — the object is cheap to rebuild from tokens
and pickling a third-party class across versions is a trap.

The memory ceiling is real: comfortable into the low hundreds of thousands of
chunks, not beyond. The escape hatch is already in place —
``ChunkRepository.search_by_text`` does the same job inside Postgres against the
stored tsvector, behind this same retriever protocol.
"""

from __future__ import annotations

import pickle
import re
import uuid
from dataclasses import dataclass, field

from rank_bm25 import BM25Okapi
from redis.asyncio import Redis

from app.core.logging import get_logger

logger = get_logger(__name__)

_KEY_PREFIX = "bm25:v1"
_TOKEN = re.compile(r"[a-z0-9]+")

# Words that appear in nearly every chunk carry no discriminating signal;
# leaving them in flattens BM25 scores toward uniform.
_STOPWORDS = frozenset(
    [
        "a",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "by",
        "for",
        "from",
        "has",
        "have",
        "he",
        "in",
        "is",
        "it",
        "its",
        "of",
        "on",
        "or",
        "that",
        "the",
        "to",
        "was",
        "were",
        "will",
        "with",
        "this",
        "these",
        "those",
        "there",
        "their",
        "them",
        "they",
        "you",
        "your",
        "we",
        "our",
        "but",
        "not",
        "can",
        "do",
        "does",
        "did",
        "if",
        "then",
        "than",
        "so",
        "such",
        "about",
        "into",
        "over",
        "under",
    ]
)


def tokenize(text: str) -> list[str]:
    return [token for token in _TOKEN.findall(text.lower()) if token not in _STOPWORDS]


@dataclass(slots=True)
class Bm25Index:
    chunk_ids: list[uuid.UUID]
    tokenised: list[list[str]]
    okapi: BM25Okapi = field(init=False)
    token_sets: list[frozenset[str]] = field(init=False)

    def __post_init__(self) -> None:
        self.okapi = BM25Okapi(self.tokenised)
        # Membership sets, so "did any query term appear here" is a set
        # intersection rather than an inference from the score.
        self.token_sets = [frozenset(tokens) for tokens in self.tokenised]

    @classmethod
    def build(cls, corpus: list[tuple[uuid.UUID, str]]) -> Bm25Index | None:
        if not corpus:
            return None

        tokenised = [tokenize(text) for _, text in corpus]
        # A corpus that tokenises to nothing would make BM25Okapi divide by an
        # average document length of zero.
        if not any(tokenised):
            return None

        return cls(chunk_ids=[chunk_id for chunk_id, _ in corpus], tokenised=tokenised)

    def top(self, query: str, limit: int) -> list[tuple[uuid.UUID, float]]:
        tokens = tokenize(query)
        if not tokens:
            return []

        query_terms = set(tokens)
        scores = self.okapi.get_scores(tokens)

        # Filter on term overlap, not on score sign. BM25 IDF goes *negative*
        # for a term present in most documents, so a score threshold would
        # discard every result for a common word — which, in a small knowledge
        # base, is most words. Rank is what fusion consumes; the sign is not
        # meaningful on its own.
        candidates = [
            (self.chunk_ids[position], float(score))
            for position, score in enumerate(scores)
            if self.token_sets[position] & query_terms
        ]
        candidates.sort(key=lambda pair: pair[1], reverse=True)
        return candidates[:limit]


class Bm25IndexCache:
    """Redis-backed cache of the tokenised corpus, keyed per knowledge base."""

    def __init__(self, redis: Redis, *, ttl_seconds: int = 3600) -> None:
        self._redis = redis
        self._ttl = ttl_seconds

    @staticmethod
    def _key(knowledge_base_id: uuid.UUID) -> str:
        return f"{_KEY_PREFIX}:{knowledge_base_id}"

    async def load(self, knowledge_base_id: uuid.UUID) -> Bm25Index | None:
        try:
            raw = await self._redis.get(self._key(knowledge_base_id))
        except Exception as exc:
            logger.warning("bm25_cache_read_failed", error=str(exc))
            return None
        if not raw:
            return None

        try:
            chunk_ids, tokenised = pickle.loads(raw)
        except Exception as exc:
            logger.warning("bm25_cache_decode_failed", error=str(exc))
            return None

        if not chunk_ids or not any(tokenised):
            return None
        return Bm25Index(chunk_ids=chunk_ids, tokenised=tokenised)

    async def store(self, knowledge_base_id: uuid.UUID, index: Bm25Index) -> None:
        try:
            payload = pickle.dumps(
                (index.chunk_ids, index.tokenised), protocol=pickle.HIGHEST_PROTOCOL
            )
            await self._redis.set(self._key(knowledge_base_id), payload, ex=self._ttl)
        except Exception as exc:
            logger.warning("bm25_cache_write_failed", error=str(exc))

    async def invalidate(self, knowledge_base_id: uuid.UUID) -> None:
        """Called whenever a document is indexed or deleted.

        A stale index keeps returning chunks that no longer exist and misses
        ones that do — neither surfaces as an error, only as worse answers.
        """
        try:
            await self._redis.delete(self._key(knowledge_base_id))
        except Exception as exc:
            logger.warning("bm25_cache_invalidate_failed", error=str(exc))
