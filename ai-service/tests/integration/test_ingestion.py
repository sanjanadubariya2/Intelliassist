"""End-to-end document ingestion against a real database.

Covers the whole path a file takes: upload, the 202 hand-off, the worker
pipeline, and the chunks that come out the other side with real 768-dimension
vectors in a pgvector column.
"""

from __future__ import annotations

import uuid

import pytest
import sqlalchemy as sa
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import ExtractionError
from app.models.document import DocumentStatus
from app.repositories.chunk_repo import ChunkRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.knowledge_base_repo import KnowledgeBaseRepository
from app.services.embeddings.fake import FakeEmbeddingProvider
from app.services.ingestion.pipeline import IngestionPipeline
from app.workers.queue import RecordingJobQueue
from tests.integration.conftest import integration_settings

pytestmark = pytest.mark.integration

HANDBOOK = b"""# Leave Policy

Full-time employees receive 25 days of paid annual leave each calendar year.
Leave must be requested at least two weeks in advance through the HR portal.

# Expenses

Travel expenses are reimbursed within 30 days of submission. Receipts are
required for any claim above 50 euros. Claims without a receipt are rejected.

# Equipment

Every new starter receives a laptop and a monitor. Replacement hardware needs
approval from a line manager and is subject to error code E4092 checks.
"""


async def make_knowledge_base(session: AsyncSession, ref: str = "org-1") -> uuid.UUID:
    settings = integration_settings()
    knowledge_base = await KnowledgeBaseRepository(session).create(
        external_ref=ref,
        name="Handbook",
        embedding_model=FakeEmbeddingProvider().model,
        embedding_dim=settings.embedding.dimension,
    )
    await session.flush()
    return knowledge_base.id


async def ingest(session: AsyncSession, knowledge_base_id: uuid.UUID, data: bytes) -> uuid.UUID:
    """Run the worker pipeline directly against the real database."""
    documents = DocumentRepository(session)
    document = await documents.create(
        knowledge_base_id=knowledge_base_id,
        filename="handbook.txt",
        mime_type="text/plain",
        content_hash=uuid.uuid4().hex,
        byte_size=len(data),
    )
    await session.flush()

    pipeline = IngestionPipeline(
        settings=integration_settings(),
        embeddings=FakeEmbeddingProvider(),
        document_repo=documents,
        chunk_repo=ChunkRepository(session),
        knowledge_base_repo=KnowledgeBaseRepository(session),
    )
    await pipeline.ingest(document_id=document.id, data=data)
    return document.id


async def test_ingestion_indexes_a_document_into_chunks(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)

    document_id = await ingest(session, knowledge_base_id, HANDBOOK)

    document = await DocumentRepository(session).get(document_id)
    assert document is not None
    assert document.status is DocumentStatus.INDEXED
    assert document.chunk_count > 0
    assert document.indexed_at is not None
    assert document.error_message is None


async def test_chunks_are_stored_with_real_vectors(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    document_id = await ingest(session, knowledge_base_id, HANDBOOK)

    row = (
        await session.execute(
            sa.text(
                "SELECT vector_dims(embedding) AS dims, token_count, knowledge_base_id "
                "FROM document_chunks WHERE document_id = :doc LIMIT 1"
            ),
            {"doc": document_id},
        )
    ).one()

    assert row.dims == 768
    assert row.token_count > 0
    assert row.knowledge_base_id == knowledge_base_id


async def test_chunk_count_matches_the_rows_written(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    document_id = await ingest(session, knowledge_base_id, HANDBOOK)

    document = await DocumentRepository(session).get(document_id)
    stored = await ChunkRepository(session).count_by_document(document_id)

    assert document is not None
    assert document.chunk_count == stored


async def test_reingesting_replaces_rather_than_duplicates(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    document_id = await ingest(session, knowledge_base_id, HANDBOOK)
    first = await ChunkRepository(session).count_by_document(document_id)

    pipeline = IngestionPipeline(
        settings=integration_settings(),
        embeddings=FakeEmbeddingProvider(),
        document_repo=DocumentRepository(session),
        chunk_repo=ChunkRepository(session),
        knowledge_base_repo=KnowledgeBaseRepository(session),
    )
    await pipeline.ingest(document_id=document_id, data=HANDBOOK)

    # Orphaned chunks from an earlier attempt would pollute retrieval silently.
    assert await ChunkRepository(session).count_by_document(document_id) == first


async def test_unreadable_document_fails_with_a_reason(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)

    # Whitespace only: nothing to extract. A scanned PDF fails the same way.
    with pytest.raises(ExtractionError):
        await ingest(session, knowledge_base_id, b"   \n\n   ")

    documents = await DocumentRepository(session).list_by_knowledge_base(knowledge_base_id)
    document = documents[0][0]
    assert document.status is DocumentStatus.FAILED
    assert document.error_message


async def test_deleting_a_document_removes_its_chunks(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    document_id = await ingest(session, knowledge_base_id, HANDBOOK)

    await ChunkRepository(session).delete_by_document(document_id)
    await DocumentRepository(session).delete(document_id)

    assert await ChunkRepository(session).count_by_knowledge_base(knowledge_base_id) == 0


async def test_deleting_a_knowledge_base_cascades_to_chunks(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    await ingest(session, knowledge_base_id, HANDBOOK)

    await KnowledgeBaseRepository(session).delete(knowledge_base_id)
    await session.flush()

    remaining = await session.scalar(
        sa.text("SELECT count(*) FROM document_chunks WHERE knowledge_base_id = :kb"),
        {"kb": knowledge_base_id},
    )
    assert remaining == 0


# ------------------------------------------------------------- upload API ---


async def test_upload_returns_202_and_queues_the_job(
    client: AsyncClient, session: AsyncSession, job_queue: RecordingJobQueue
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-upload")
    await session.commit()

    response = await client.post(
        "/api/v1/knowledge/documents",
        data={"knowledge_base_id": str(knowledge_base_id)},
        files={"file": ("handbook.txt", HANDBOOK, "text/plain")},
    )

    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "pending"
    assert body["duplicate"] is False
    assert body["poll_url"].endswith(body["id"])
    assert len(job_queue.enqueued) == 1


async def test_uploading_the_same_bytes_twice_is_idempotent(
    client: AsyncClient, session: AsyncSession, job_queue: RecordingJobQueue
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-dup")
    await session.commit()

    payload = {
        "data": {"knowledge_base_id": str(knowledge_base_id)},
        "files": {"file": ("handbook.txt", HANDBOOK, "text/plain")},
    }
    first = await client.post("/api/v1/knowledge/documents", **payload)  # type: ignore[arg-type]
    second = await client.post("/api/v1/knowledge/documents", **payload)  # type: ignore[arg-type]

    assert second.json()["duplicate"] is True
    assert second.json()["id"] == first.json()["id"]
    # The second upload must not queue a second ingestion of the same corpus.
    assert len(job_queue.enqueued) == 1


async def test_upload_to_a_missing_knowledge_base_is_404(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/knowledge/documents",
        data={"knowledge_base_id": str(uuid.uuid4())},
        files={"file": ("a.txt", b"content", "text/plain")},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


async def test_unsupported_file_type_is_rejected(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-type")
    await session.commit()

    response = await client.post(
        "/api/v1/knowledge/documents",
        data={"knowledge_base_id": str(knowledge_base_id)},
        files={"file": ("image.png", b"\x89PNG\r\n\x1a\n" + b"0" * 32, "image/png")},
    )

    assert response.status_code == 415


async def test_empty_file_is_rejected(client: AsyncClient, session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-empty")
    await session.commit()

    response = await client.post(
        "/api/v1/knowledge/documents",
        data={"knowledge_base_id": str(knowledge_base_id)},
        files={"file": ("empty.txt", b"", "text/plain")},
    )

    assert response.status_code == 415


async def test_status_endpoint_reports_progress(client: AsyncClient, session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-status")
    document_id = await ingest(session, knowledge_base_id, HANDBOOK)
    await session.commit()

    response = await client.get(f"/api/v1/knowledge/documents/{document_id}")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "indexed"
    assert body["chunk_count"] > 0
    # The ORM model must never leak through the contract.
    assert "meta" not in body and "metadata" in body


async def test_status_of_an_unknown_document_is_404(client: AsyncClient) -> None:
    response = await client.get(f"/api/v1/knowledge/documents/{uuid.uuid4()}")

    assert response.status_code == 404
