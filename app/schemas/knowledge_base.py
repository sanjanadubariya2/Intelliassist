"""Public knowledge-base contract."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.models.knowledge_base import KnowledgeBase, KnowledgeBaseStatus


class KnowledgeBaseCreate(BaseModel):
    external_ref: str = Field(
        min_length=1,
        max_length=255,
        description=(
            "Opaque identifier owned by the calling service. Spring Boot puts its "
            "tenant identifier here; this service never interprets it."
        ),
        examples=["org_9f2c41"],
    )
    name: str = Field(min_length=1, max_length=255, examples=["Employee Handbook"])
    description: str | None = Field(default=None, max_length=2000)
    metadata: dict[str, Any] = Field(default_factory=dict)


class KnowledgeBaseResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    external_ref: str
    name: str
    description: str | None
    status: KnowledgeBaseStatus
    embedding_model: str = Field(description="Frozen at creation; vectors are model-specific.")
    embedding_dim: int
    metadata: dict[str, Any]
    created_at: datetime
    updated_at: datetime

    @classmethod
    def from_model(cls, knowledge_base: KnowledgeBase) -> KnowledgeBaseResponse:
        # Built explicitly rather than by attribute scan: the ORM attribute is
        # `meta` (because `metadata` is reserved) while the contract says
        # `metadata`, and that mismatch should be stated once, here.
        return cls(
            id=knowledge_base.id,
            external_ref=knowledge_base.external_ref,
            name=knowledge_base.name,
            description=knowledge_base.description,
            status=knowledge_base.status,
            embedding_model=knowledge_base.embedding_model,
            embedding_dim=knowledge_base.embedding_dim,
            metadata=knowledge_base.meta,
            created_at=knowledge_base.created_at,
            updated_at=knowledge_base.updated_at,
        )


class KnowledgeBaseStats(BaseModel):
    knowledge_base_id: uuid.UUID
    document_count: int
    chunk_count: int
    indexed_document_count: int
    failed_document_count: int
