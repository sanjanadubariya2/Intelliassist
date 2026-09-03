"""Public document contract."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.models.document import Document, DocumentStatus


class DocumentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    knowledge_base_id: uuid.UUID
    filename: str
    mime_type: str
    status: DocumentStatus = Field(
        description=(
            "pending -> extracting -> chunking -> embedding -> indexed, or failed. "
            "A document is queryable only once it reaches 'indexed'."
        )
    )
    byte_size: int
    page_count: int | None
    chunk_count: int
    error_message: str | None = Field(
        default=None, description="Populated only when status is 'failed'."
    )
    metadata: dict[str, Any]
    created_at: datetime
    indexed_at: datetime | None

    @classmethod
    def from_model(cls, document: Document) -> DocumentResponse:
        return cls(
            id=document.id,
            knowledge_base_id=document.knowledge_base_id,
            filename=document.filename,
            mime_type=document.mime_type,
            status=document.status,
            byte_size=document.byte_size,
            page_count=document.page_count,
            chunk_count=document.chunk_count,
            error_message=document.error_message,
            metadata=document.meta,
            created_at=document.created_at,
            indexed_at=document.indexed_at,
        )


class DocumentAcceptedResponse(BaseModel):
    """Returned by upload. 202 with an id — ingestion happens on a worker."""

    id: uuid.UUID
    knowledge_base_id: uuid.UUID
    filename: str
    status: DocumentStatus
    duplicate: bool = Field(
        default=False,
        description=(
            "True when these exact bytes were already ingested into this knowledge "
            "base. The existing document is returned and no new work is queued."
        ),
    )
    poll_url: str = Field(description="Where to check ingestion progress.")
