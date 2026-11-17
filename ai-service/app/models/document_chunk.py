"""The retrievable unit, and the thing a citation points at.

``knowledge_base_id`` is denormalised here on purpose. Every retrieval query
filters on it, and carrying it on the chunk means isolation is enforced by a
single predicate with no join to ``documents`` — one column that cannot be
forgotten rather than a join that can.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING, Any

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    Computed,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, TSVECTOR, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.config import get_settings
from app.db.base import Base, UUIDPrimaryKeyMixin

if TYPE_CHECKING:
    from app.models.document import Document

# The vector column width is a schema fact fixed by the migration, so it is read
# once here from the single place dimensions are configured. Changing it means a
# migration and a full re-embed — see docs in app/core/config.py.
EMBEDDING_DIMENSION = get_settings().embedding.dimension

# Text search configuration for the generated tsvector. Kept here rather than
# inline so the BM25 fallback retriever and the migration agree.
TEXT_SEARCH_CONFIG = "english"


class DocumentChunk(UUIDPrimaryKeyMixin, Base):
    __tablename__ = "document_chunks"

    knowledge_base_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("knowledge_bases.id", ondelete="CASCADE"),
        nullable=False,
    )
    document_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("documents.id", ondelete="CASCADE"),
        nullable=False,
    )

    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)

    # Precomputed so context budgeting at query time needs no tokeniser.
    token_count: Mapped[int] = mapped_column(Integer, nullable=False)

    embedding: Mapped[list[float]] = mapped_column(Vector(EMBEDDING_DIMENSION), nullable=False)

    # PDFs carry a page; DOCX and TXT usually do not.
    page_number: Mapped[int | None] = mapped_column(Integer, nullable=True)

    # Offsets into the cleaned document text, for highlight-in-source later.
    char_start: Mapped[int | None] = mapped_column(Integer, nullable=True)
    char_end: Mapped[int | None] = mapped_column(Integer, nullable=True)

    content_hash: Mapped[str] = mapped_column(String(64), nullable=False)

    meta: Mapped[dict[str, Any]] = mapped_column(
        "metadata", JSONB, nullable=False, default=dict, server_default="{}"
    )

    # Maintained by Postgres. Backs the lexical fallback retriever and keeps the
    # option of moving BM25 into the database when rank-bm25's in-memory index
    # stops fitting.
    tsv: Mapped[str] = mapped_column(
        TSVECTOR,
        Computed(f"to_tsvector('{TEXT_SEARCH_CONFIG}', text)", persisted=True),
        nullable=True,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    document: Mapped[Document] = relationship(back_populates="chunks")

    __table_args__ = (
        UniqueConstraint("document_id", "chunk_index", name="uq_document_chunks_document_index"),
        Index("ix_document_chunks_knowledge_base_id", "knowledge_base_id"),
        Index("ix_document_chunks_kb_document", "knowledge_base_id", "document_id"),
        Index(
            "ix_document_chunks_tsv",
            "tsv",
            postgresql_using="gin",
        ),
        Index(
            "ix_document_chunks_embedding_hnsw",
            "embedding",
            postgresql_using="hnsw",
            postgresql_with={"m": 16, "ef_construction": 64},
            postgresql_ops={"embedding": "vector_cosine_ops"},
        ),
    )

    def __repr__(self) -> str:
        return f"<DocumentChunk {self.id} doc={self.document_id} #{self.chunk_index}>"
