"""Document persistence.

Every read that can return a document takes a ``knowledge_base_id`` so a caller
cannot fetch across knowledge bases by guessing an id.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.document import Document, DocumentStatus, SourceType
from app.repositories.base import affected_rows


class DocumentRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(
        self,
        *,
        knowledge_base_id: uuid.UUID,
        filename: str,
        mime_type: str,
        content_hash: str,
        byte_size: int,
        source_type: SourceType = SourceType.UPLOAD,
        meta: dict[str, object] | None = None,
    ) -> Document:
        document = Document(
            knowledge_base_id=knowledge_base_id,
            filename=filename,
            mime_type=mime_type,
            content_hash=content_hash,
            byte_size=byte_size,
            source_type=source_type,
            status=DocumentStatus.PENDING,
            meta=meta or {},
        )
        self._session.add(document)
        await self._session.flush()
        return document

    async def get(
        self, document_id: uuid.UUID, *, knowledge_base_id: uuid.UUID | None = None
    ) -> Document | None:
        conditions = [Document.id == document_id]
        if knowledge_base_id is not None:
            conditions.append(Document.knowledge_base_id == knowledge_base_id)

        result = await self._session.execute(select(Document).where(*conditions))
        return result.scalar_one_or_none()

    async def get_by_content_hash(
        self, *, knowledge_base_id: uuid.UUID, content_hash: str
    ) -> Document | None:
        """Backs upload idempotency: same bytes, same knowledge base, same document."""
        result = await self._session.execute(
            select(Document).where(
                Document.knowledge_base_id == knowledge_base_id,
                Document.content_hash == content_hash,
            )
        )
        return result.scalar_one_or_none()

    async def list_by_knowledge_base(
        self,
        knowledge_base_id: uuid.UUID,
        *,
        limit: int = 50,
        offset: int = 0,
        status: DocumentStatus | None = None,
    ) -> tuple[list[Document], int]:
        conditions = [Document.knowledge_base_id == knowledge_base_id]
        if status is not None:
            conditions.append(Document.status == status)

        total = await self._session.scalar(
            select(func.count()).select_from(Document).where(*conditions)
        )
        result = await self._session.execute(
            select(Document)
            .where(*conditions)
            .order_by(Document.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all()), int(total or 0)

    async def set_status(
        self,
        document_id: uuid.UUID,
        status: DocumentStatus,
        *,
        error_message: str | None = None,
        chunk_count: int | None = None,
        page_count: int | None = None,
    ) -> Document | None:
        document = await self.get(document_id)
        if document is None:
            return None

        document.status = status
        # Clear a stale failure when a retry starts making progress.
        document.error_message = error_message
        if chunk_count is not None:
            document.chunk_count = chunk_count
        if page_count is not None:
            document.page_count = page_count
        if status is DocumentStatus.INDEXED:
            document.indexed_at = datetime.now(UTC)

        await self._session.flush()
        return document

    async def delete(
        self, document_id: uuid.UUID, *, knowledge_base_id: uuid.UUID | None = None
    ) -> bool:
        conditions = [Document.id == document_id]
        if knowledge_base_id is not None:
            conditions.append(Document.knowledge_base_id == knowledge_base_id)

        result = await self._session.execute(delete(Document).where(*conditions))
        return affected_rows(result) > 0
