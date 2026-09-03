"""Dense (semantic) retrieval.

Embeds the query and hands it to the chunk repository. Deliberately thin: the
SQL, the HNSW tuning and the isolation predicate all live in the repository, so
this class is just the embedding step plus a call.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence

from app.repositories.chunk_repo import ChunkRepository
from app.schemas.retrieval import ScoredChunk
from app.services.embeddings.base import EmbeddingProvider, EmbeddingTask


class DenseRetriever:
    def __init__(
        self,
        *,
        chunk_repo: ChunkRepository,
        embeddings: EmbeddingProvider,
        ef_search: int = 100,
    ) -> None:
        self._chunks = chunk_repo
        self._embeddings = embeddings
        self._ef_search = ef_search

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

        # QUERY, not DOCUMENT: retrieval is asymmetric and the provider encodes
        # the two sides differently.
        vector = await self._embeddings.embed_one(query, task=EmbeddingTask.QUERY)

        return await self._chunks.search_by_vector(
            knowledge_base_id=knowledge_base_id,
            embedding=vector,
            limit=limit,
            ef_search=self._ef_search,
            document_ids=document_ids,
        )
