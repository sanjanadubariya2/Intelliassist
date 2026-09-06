"""Knowledge base persistence."""

from __future__ import annotations

import uuid

from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import ConflictError
from app.models.knowledge_base import KnowledgeBase, KnowledgeBaseStatus
from app.repositories.base import affected_rows


class KnowledgeBaseRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(
        self,
        *,
        external_ref: str,
        name: str,
        embedding_model: str,
        embedding_dim: int,
        description: str | None = None,
        meta: dict[str, object] | None = None,
    ) -> KnowledgeBase:
        knowledge_base = KnowledgeBase(
            external_ref=external_ref,
            name=name,
            description=description,
            embedding_model=embedding_model,
            embedding_dim=embedding_dim,
            meta=meta or {},
        )
        self._session.add(knowledge_base)
        try:
            await self._session.flush()
        except IntegrityError as exc:
            await self._session.rollback()
            raise ConflictError(
                f"A knowledge base with external_ref {external_ref!r} already exists.",
                details={"external_ref": external_ref},
            ) from exc
        return knowledge_base

    async def get(self, knowledge_base_id: uuid.UUID) -> KnowledgeBase | None:
        return await self._session.get(KnowledgeBase, knowledge_base_id)

    async def get_by_external_ref(self, external_ref: str) -> KnowledgeBase | None:
        result = await self._session.execute(
            select(KnowledgeBase).where(KnowledgeBase.external_ref == external_ref)
        )
        return result.scalar_one_or_none()

    async def list(
        self, *, limit: int = 50, offset: int = 0, status: KnowledgeBaseStatus | None = None
    ) -> tuple[list[KnowledgeBase], int]:
        conditions = []
        if status is not None:
            conditions.append(KnowledgeBase.status == status)

        total = await self._session.scalar(
            select(func.count()).select_from(KnowledgeBase).where(*conditions)
        )
        result = await self._session.execute(
            select(KnowledgeBase)
            .where(*conditions)
            .order_by(KnowledgeBase.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all()), int(total or 0)

    async def delete(self, knowledge_base_id: uuid.UUID) -> bool:
        """Hard delete. Documents and chunks go with it via ON DELETE CASCADE."""
        result = await self._session.execute(
            delete(KnowledgeBase).where(KnowledgeBase.id == knowledge_base_id)
        )
        return affected_rows(result) > 0

    async def archive(self, knowledge_base_id: uuid.UUID) -> KnowledgeBase | None:
        knowledge_base = await self.get(knowledge_base_id)
        if knowledge_base is None:
            return None
        knowledge_base.status = KnowledgeBaseStatus.ARCHIVED
        await self._session.flush()
        return knowledge_base
