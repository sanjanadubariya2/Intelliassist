"""Knowledge base and document endpoints."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Query, status

from app.api.deps import SessionDep, SettingsDep
from app.core.exceptions import NotFoundError
from app.models.document import DocumentStatus
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.schemas.common import Page
from app.schemas.knowledge_base import (
    KnowledgeBaseCreate,
    KnowledgeBaseResponse,
    KnowledgeBaseStats,
)

router = APIRouter(tags=["knowledge"])


@router.post(
    "/knowledge-bases",
    response_model=KnowledgeBaseResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a knowledge base",
)
async def create_knowledge_base(
    payload: KnowledgeBaseCreate,
    session: SessionDep,
    settings: SettingsDep,
) -> KnowledgeBaseResponse:
    """Create an isolated body of knowledge.

    The embedding model and dimension are stamped from current configuration and
    frozen: vectors built by different models are not comparable, so a knowledge
    base stays bound to the model that indexed it.
    """
    repo = KnowledgeBaseRepository(session)
    knowledge_base = await repo.create(
        external_ref=payload.external_ref,
        name=payload.name,
        description=payload.description,
        embedding_model=settings.embedding.model,
        embedding_dim=settings.embedding.dimension,
        meta=payload.metadata,
    )
    return KnowledgeBaseResponse.from_model(knowledge_base)


@router.get(
    "/knowledge-bases",
    response_model=Page[KnowledgeBaseResponse],
    summary="List knowledge bases",
)
async def list_knowledge_bases(
    session: SessionDep,
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
) -> Page[KnowledgeBaseResponse]:
    items, total = await KnowledgeBaseRepository(session).list(limit=limit, offset=offset)
    return Page(
        items=[KnowledgeBaseResponse.from_model(item) for item in items],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get(
    "/knowledge-bases/{knowledge_base_id}",
    response_model=KnowledgeBaseResponse,
    summary="Get a knowledge base",
)
async def get_knowledge_base(
    knowledge_base_id: uuid.UUID,
    session: SessionDep,
) -> KnowledgeBaseResponse:
    knowledge_base = await KnowledgeBaseRepository(session).get(knowledge_base_id)
    if knowledge_base is None:
        raise NotFoundError(
            f"Knowledge base {knowledge_base_id} does not exist.",
            details={"knowledge_base_id": str(knowledge_base_id)},
        )
    return KnowledgeBaseResponse.from_model(knowledge_base)


@router.get(
    "/knowledge-bases/{knowledge_base_id}/stats",
    response_model=KnowledgeBaseStats,
    summary="Document and chunk counts",
)
async def get_knowledge_base_stats(
    knowledge_base_id: uuid.UUID,
    session: SessionDep,
) -> KnowledgeBaseStats:
    """How much of this knowledge base is actually queryable.

    Chunk count is the number that matters: a knowledge base with documents but
    no chunks will answer nothing, and that is otherwise invisible.
    """
    kb_repo = KnowledgeBaseRepository(session)
    if await kb_repo.get(knowledge_base_id) is None:
        raise NotFoundError(f"Knowledge base {knowledge_base_id} does not exist.")

    doc_repo = DocumentRepository(session)
    _, total_documents = await doc_repo.list_by_knowledge_base(knowledge_base_id, limit=1)
    _, indexed = await doc_repo.list_by_knowledge_base(
        knowledge_base_id, limit=1, status=DocumentStatus.INDEXED
    )
    _, failed = await doc_repo.list_by_knowledge_base(
        knowledge_base_id, limit=1, status=DocumentStatus.FAILED
    )
    chunk_count = await ChunkRepository(session).count_by_knowledge_base(knowledge_base_id)

    return KnowledgeBaseStats(
        knowledge_base_id=knowledge_base_id,
        document_count=total_documents,
        chunk_count=chunk_count,
        indexed_document_count=indexed,
        failed_document_count=failed,
    )


@router.delete(
    "/knowledge-bases/{knowledge_base_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a knowledge base and everything in it",
)
async def delete_knowledge_base(
    knowledge_base_id: uuid.UUID,
    session: SessionDep,
) -> None:
    deleted = await KnowledgeBaseRepository(session).delete(knowledge_base_id)
    if not deleted:
        raise NotFoundError(f"Knowledge base {knowledge_base_id} does not exist.")
