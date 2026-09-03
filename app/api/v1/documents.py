"""Document ingestion endpoints.

Upload returns 202 and a document id. A 200-page PDF is minutes of extraction
and embedding; doing that inside the request would guarantee gateway timeouts,
so the work goes to a worker and the caller polls the status endpoint.
"""

from __future__ import annotations

import hashlib
import uuid

from fastapi import APIRouter, File, Form, UploadFile, status

from app.api.deps import BlobStoreDep, JobQueueDep, RedisDep, SessionDep, SettingsDep
from app.core.exceptions import (
    NotFoundError,
    PayloadTooLargeError,
    UnsupportedDocumentTypeError,
)
from app.core.logging import get_logger
from app.models.document import DocumentStatus
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.schemas.common import Page
from app.schemas.document import DocumentAcceptedResponse, DocumentResponse
from app.services.ingestion.extractors.registry import ExtractorRegistry
from app.services.retrieval.index.bm25_index import Bm25IndexCache

logger = get_logger(__name__)
router = APIRouter(tags=["documents"])

_registry = ExtractorRegistry()


@router.post(
    "/knowledge/documents",
    response_model=DocumentAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload a document for ingestion",
)
async def upload_document(
    session: SessionDep,
    settings: SettingsDep,
    queue: JobQueueDep,
    blobs: BlobStoreDep,
    redis: RedisDep,
    knowledge_base_id: uuid.UUID = Form(...),
    file: UploadFile = File(...),
) -> DocumentAcceptedResponse:
    knowledge_base = await KnowledgeBaseRepository(session).get(knowledge_base_id)
    if knowledge_base is None:
        raise NotFoundError(
            f"Knowledge base {knowledge_base_id} does not exist.",
            details={"knowledge_base_id": str(knowledge_base_id)},
        )

    data = await file.read()
    if not data:
        raise UnsupportedDocumentTypeError("The uploaded file is empty.")
    if len(data) > settings.ingestion.max_file_bytes:
        raise PayloadTooLargeError(
            f"File exceeds the {settings.ingestion.max_file_bytes // (1024 * 1024)} MB limit.",
            details={"byte_size": len(data)},
        )

    # Sniffed from content, not trusted from the client — a browser will happily
    # send application/octet-stream, and a caller can send anything at all.
    mime_type = _registry.detect_mime_type(
        data, filename=file.filename or "upload", declared=file.content_type
    )
    if mime_type not in settings.ingestion.allowed_mime_types:
        raise UnsupportedDocumentTypeError(
            f"{mime_type} is not supported. Supported: "
            f"{', '.join(settings.ingestion.allowed_mime_types)}.",
            details={"detected_mime_type": mime_type},
        )

    content_hash = hashlib.sha256(data).hexdigest()
    documents = DocumentRepository(session)

    existing = await documents.get_by_content_hash(
        knowledge_base_id=knowledge_base_id, content_hash=content_hash
    )
    if existing is not None:
        # Same bytes, same knowledge base: return what we already have instead
        # of indexing the corpus twice. Makes client retries safe.
        return DocumentAcceptedResponse(
            id=existing.id,
            knowledge_base_id=knowledge_base_id,
            filename=existing.filename,
            status=existing.status,
            duplicate=True,
            poll_url=f"/api/v1/knowledge/documents/{existing.id}",
        )

    document = await documents.create(
        knowledge_base_id=knowledge_base_id,
        filename=file.filename or "upload",
        mime_type=mime_type,
        content_hash=content_hash,
        byte_size=len(data),
    )

    await blobs.put(document.id, data)
    await queue.enqueue_ingestion(document.id)
    # The corpus is about to change; a cached BM25 index would go stale.
    await Bm25IndexCache(redis).invalidate(knowledge_base_id)

    logger.info(
        "document_accepted",
        document_id=str(document.id),
        knowledge_base_id=str(knowledge_base_id),
        mime_type=mime_type,
        byte_size=len(data),
    )
    return DocumentAcceptedResponse(
        id=document.id,
        knowledge_base_id=knowledge_base_id,
        filename=document.filename,
        status=document.status,
        duplicate=False,
        poll_url=f"/api/v1/knowledge/documents/{document.id}",
    )


@router.get(
    "/knowledge/documents/{document_id}",
    response_model=DocumentResponse,
    summary="Ingestion status of a document",
)
async def get_document(document_id: uuid.UUID, session: SessionDep) -> DocumentResponse:
    """Poll until `status` is `indexed`; `failed` carries `error_message`."""
    document = await DocumentRepository(session).get(document_id)
    if document is None:
        raise NotFoundError(f"Document {document_id} does not exist.")
    return DocumentResponse.from_model(document)


@router.get(
    "/knowledge-bases/{knowledge_base_id}/documents",
    response_model=Page[DocumentResponse],
    summary="List documents in a knowledge base",
)
async def list_documents(
    knowledge_base_id: uuid.UUID,
    session: SessionDep,
    limit: int = 50,
    offset: int = 0,
    document_status: DocumentStatus | None = None,
) -> Page[DocumentResponse]:
    items, total = await DocumentRepository(session).list_by_knowledge_base(
        knowledge_base_id, limit=limit, offset=offset, status=document_status
    )
    return Page(
        items=[DocumentResponse.from_model(item) for item in items],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.delete(
    "/knowledge/documents/{document_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Delete a document and its chunks",
)
async def delete_document(
    document_id: uuid.UUID,
    session: SessionDep,
    redis: RedisDep,
    blobs: BlobStoreDep,
) -> None:
    documents = DocumentRepository(session)
    document = await documents.get(document_id)
    if document is None:
        raise NotFoundError(f"Document {document_id} does not exist.")

    knowledge_base_id = document.knowledge_base_id
    await ChunkRepository(session).delete_by_document(document_id)
    await documents.delete(document_id)

    await blobs.delete(document_id)
    await Bm25IndexCache(redis).invalidate(knowledge_base_id)

    logger.info("document_deleted", document_id=str(document_id))
