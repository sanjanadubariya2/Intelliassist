"""BM25 (lexical) retrieval.

Complements dense retrieval on the cases embeddings are weakest at: exact
identifiers, product names, error codes, acronyms — anything where the literal
token matters more than the meaning.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence

from app.repositories.chunk_repo import ChunkRepository
from app.schemas.retrieval import RetrievalSource, ScoredChunk
from app.services.retrieval.index.bm25_index import Bm25Index, Bm25IndexCache


class Bm25Retriever:
    def __init__(
        self,
        *,
        chunk_repo: ChunkRepository,
        cache: Bm25IndexCache | None = None,
    ) -> None:
        self._chunks = chunk_repo
        self._cache = cache

    async def retrieve(
        self,
        *,
        query: str,
        knowledge_base_id: uuid.UUID,
        limit: int,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> list[ScoredChunk]:
        if not query.strip():
            return []

        index = await self._index_for(knowledge_base_id)
        if index is None:
            return []

        # Over-fetch when filtering to documents, since the index is built over
        # the whole knowledge base and the filter is applied after scoring.
        fetch = limit * 4 if document_ids else limit
        ranked = index.top(query, fetch)
        if not ranked:
            return []

        scores = dict(ranked)
        chunks = await self._chunks.get_by_ids(
            knowledge_base_id=knowledge_base_id,
            chunk_ids=[chunk_id for chunk_id, _ in ranked],
        )

        allowed = set(document_ids) if document_ids else None
        results = [
            ScoredChunk(
                chunk=chunk,
                score=scores.get(chunk.chunk_id, 0.0),
                source=RetrievalSource.BM25,
            )
            for chunk in chunks
            if allowed is None or chunk.document_id in allowed
        ]
        return results[:limit]

    async def _index_for(self, knowledge_base_id: uuid.UUID) -> Bm25Index | None:
        if self._cache is not None:
            cached = await self._cache.load(knowledge_base_id)
            if cached is not None:
                return cached

        corpus = await self._chunks.iter_corpus_for_bm25(knowledge_base_id)
        index = Bm25Index.build(corpus)

        if index is not None and self._cache is not None:
            await self._cache.store(knowledge_base_id, index)
        return index
