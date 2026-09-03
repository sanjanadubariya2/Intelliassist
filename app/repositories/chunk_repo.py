"""Chunk persistence and vector search.

All SQL that touches embeddings lives here. Every query filters on
``knowledge_base_id``; there is no method that can read across knowledge bases.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import Any

from sqlalchemy import Select, delete, func, insert, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.document import Document
from app.models.document_chunk import DocumentChunk
from app.repositories.base import affected_rows
from app.schemas.retrieval import RetrievalSource, RetrievedChunk, ScoredChunk


class ChunkRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    # ------------------------------------------------------------- writes ---

    async def bulk_insert(self, chunks: Sequence[dict[str, Any]]) -> int:
        """Insert many chunks in one statement.

        A 200-page PDF is thousands of rows; adding them one at a time is the
        difference between one round trip and thousands.
        """
        if not chunks:
            return 0
        await self._session.execute(insert(DocumentChunk), list(chunks))
        return len(chunks)

    async def delete_by_document(self, document_id: uuid.UUID) -> int:
        result = await self._session.execute(
            delete(DocumentChunk).where(DocumentChunk.document_id == document_id)
        )
        return affected_rows(result)

    # -------------------------------------------------------------- reads ---

    async def count_by_knowledge_base(self, knowledge_base_id: uuid.UUID) -> int:
        total = await self._session.scalar(
            select(func.count())
            .select_from(DocumentChunk)
            .where(DocumentChunk.knowledge_base_id == knowledge_base_id)
        )
        return int(total or 0)

    async def count_by_document(self, document_id: uuid.UUID) -> int:
        total = await self._session.scalar(
            select(func.count())
            .select_from(DocumentChunk)
            .where(DocumentChunk.document_id == document_id)
        )
        return int(total or 0)

    async def get_by_ids(
        self, *, knowledge_base_id: uuid.UUID, chunk_ids: Sequence[uuid.UUID]
    ) -> list[RetrievedChunk]:
        """Hydrate chunks by id, scoped to one knowledge base.

        BM25 works on ids alone, so this is how sparse hits become real chunks.
        """
        if not chunk_ids:
            return []

        result = await self._session.execute(
            self._base_select().where(
                DocumentChunk.knowledge_base_id == knowledge_base_id,
                DocumentChunk.id.in_(chunk_ids),
            )
        )
        by_id = {row.id: self._to_dto(row) for row in result}
        # Preserve the caller's ordering, which carries the ranking.
        return [by_id[chunk_id] for chunk_id in chunk_ids if chunk_id in by_id]

    async def iter_corpus_for_bm25(
        self, knowledge_base_id: uuid.UUID
    ) -> list[tuple[uuid.UUID, str]]:
        """Everything the BM25 index needs: chunk id and text, nothing else.

        Deliberately narrow — pulling embeddings here would move megabytes of
        float per index rebuild for no benefit.
        """
        result = await self._session.execute(
            select(DocumentChunk.id, DocumentChunk.text)
            .where(DocumentChunk.knowledge_base_id == knowledge_base_id)
            .order_by(DocumentChunk.created_at, DocumentChunk.id)
        )
        return [(row.id, row.text) for row in result]

    async def get_neighbours(
        self,
        *,
        knowledge_base_id: uuid.UUID,
        document_id: uuid.UUID,
        chunk_index: int,
        window: int = 1,
    ) -> list[RetrievedChunk]:
        """Adjacent chunks, for restoring continuity a chunk boundary cut."""
        result = await self._session.execute(
            self._base_select()
            .where(
                DocumentChunk.knowledge_base_id == knowledge_base_id,
                DocumentChunk.document_id == document_id,
                DocumentChunk.chunk_index.between(chunk_index - window, chunk_index + window),
            )
            .order_by(DocumentChunk.chunk_index)
        )
        return [self._to_dto(row) for row in result]

    # ------------------------------------------------------ vector search ---

    async def search_by_vector(
        self,
        *,
        knowledge_base_id: uuid.UUID,
        embedding: Sequence[float],
        limit: int,
        ef_search: int = 100,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> list[ScoredChunk]:
        """Approximate nearest neighbours within one knowledge base.

        Two session settings matter here and are easy to omit:

        ``hnsw.ef_search`` widens the candidate list the index walk keeps, which
        is the main recall dial.

        ``hnsw.iterative_scan`` is what makes a *filtered* search correct. HNSW
        applies the ``knowledge_base_id`` predicate after the index walk, so
        without iterative scanning a query against a small knowledge base in a
        large table quietly returns fewer rows than asked for. ``relaxed_order``
        keeps scanning until the limit is satisfied. It needs pgvector >= 0.8.
        """
        await self._session.execute(text(f"SET LOCAL hnsw.ef_search = {int(ef_search)}"))
        await self._session.execute(text("SET LOCAL hnsw.iterative_scan = 'relaxed_order'"))

        distance = DocumentChunk.embedding.cosine_distance(list(embedding)).label("distance")

        conditions = [DocumentChunk.knowledge_base_id == knowledge_base_id]
        if document_ids:
            conditions.append(DocumentChunk.document_id.in_(document_ids))

        result = await self._session.execute(
            self._base_select(distance).where(*conditions).order_by(distance).limit(limit)
        )

        # pgvector returns cosine *distance* in [0, 2]; similarity is 1 - d, so
        # a higher score is a better match, consistent with every other stage.
        return [
            ScoredChunk(
                chunk=self._to_dto(row),
                score=1.0 - float(row.distance),
                source=RetrievalSource.DENSE,
            )
            for row in result
        ]

    async def search_by_text(
        self,
        *,
        knowledge_base_id: uuid.UUID,
        query: str,
        limit: int,
        document_ids: Sequence[uuid.UUID] | None = None,
    ) -> list[ScoredChunk]:
        """Postgres full-text ranking over the stored tsvector.

        The scale path for sparse retrieval: rank-bm25 holds a per-knowledge-base
        index in process memory, which stops being reasonable somewhere in the
        low hundreds of thousands of chunks. This stays in the database and
        respects the knowledge-base filter natively.
        """
        ts_query = func.websearch_to_tsquery("english", query)
        rank = func.ts_rank_cd(DocumentChunk.tsv, ts_query).label("rank")

        conditions = [
            DocumentChunk.knowledge_base_id == knowledge_base_id,
            DocumentChunk.tsv.op("@@")(ts_query),
        ]
        if document_ids:
            conditions.append(DocumentChunk.document_id.in_(document_ids))

        result = await self._session.execute(
            self._base_select(rank).where(*conditions).order_by(rank.desc()).limit(limit)
        )
        return [
            ScoredChunk(
                chunk=self._to_dto(row),
                score=float(row.rank),
                source=RetrievalSource.BM25,
            )
            for row in result
        ]

    # ------------------------------------------------------------ helpers ---

    @staticmethod
    def _base_select(*extra: Any) -> Select[Any]:
        """Chunk columns plus the filename a citation needs.

        The embedding is deliberately not selected — it is 768 floats per row
        that nothing downstream reads.
        """
        return (
            select(
                DocumentChunk.id,
                DocumentChunk.document_id,
                DocumentChunk.knowledge_base_id,
                DocumentChunk.chunk_index,
                DocumentChunk.text,
                DocumentChunk.token_count,
                DocumentChunk.page_number,
                DocumentChunk.meta,
                Document.filename,
                *extra,
            )
            .select_from(DocumentChunk)
            .join(Document, Document.id == DocumentChunk.document_id)
        )

    @staticmethod
    def _to_dto(row: Any) -> RetrievedChunk:
        return RetrievedChunk(
            chunk_id=row.id,
            document_id=row.document_id,
            knowledge_base_id=row.knowledge_base_id,
            chunk_index=row.chunk_index,
            text=row.text,
            token_count=row.token_count,
            filename=row.filename,
            page_number=row.page_number,
            meta=row.meta or {},
        )
