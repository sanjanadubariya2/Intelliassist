"""Phase 1 gate: the migration applies, and a knowledge base survives a
round trip through the real database."""

from __future__ import annotations

import uuid

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

pytestmark = pytest.mark.integration


async def _create(client: AsyncClient, **overrides: object) -> dict:
    payload = {
        "external_ref": f"org_{uuid.uuid4().hex[:10]}",
        "name": "Employee Handbook",
        "description": "HR policies",
        "metadata": {"team": "people-ops"},
    }
    payload.update(overrides)  # type: ignore[arg-type]
    response = await client.post("/api/v1/knowledge-bases", json=payload)
    assert response.status_code == 201, response.text
    return response.json()


async def test_create_and_fetch_round_trip(client: AsyncClient) -> None:
    created = await _create(client)

    assert created["status"] == "active"
    assert created["metadata"] == {"team": "people-ops"}

    fetched = await client.get(f"/api/v1/knowledge-bases/{created['id']}")
    assert fetched.status_code == 200
    assert fetched.json()["id"] == created["id"]


async def test_embedding_model_and_dimension_are_frozen_at_creation(
    client: AsyncClient,
) -> None:
    # Vectors from different models are not comparable, so the knowledge base
    # records which model built it.
    created = await _create(client)

    assert created["embedding_model"] == "gemini-embedding-001"
    assert created["embedding_dim"] == 768


async def test_duplicate_external_ref_is_a_conflict_not_a_500(client: AsyncClient) -> None:
    created = await _create(client)

    response = await client.post(
        "/api/v1/knowledge-bases",
        json={"external_ref": created["external_ref"], "name": "Second attempt"},
    )

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "conflict"


async def test_unknown_knowledge_base_is_404_with_the_error_envelope(
    client: AsyncClient,
) -> None:
    response = await client.get(f"/api/v1/knowledge-bases/{uuid.uuid4()}")

    assert response.status_code == 404
    error = response.json()["error"]
    assert error["code"] == "not_found"
    assert error["request_id"]


async def test_no_sqlalchemy_internals_leak_into_the_response(client: AsyncClient) -> None:
    created = await _create(client)

    # The ORM attribute is `meta`; the contract says `metadata`. Neither the
    # internal name nor SQLAlchemy's instance state may appear.
    assert "meta" not in created
    assert "_sa_instance_state" not in created
    assert set(created) == {
        "id",
        "external_ref",
        "name",
        "description",
        "status",
        "embedding_model",
        "embedding_dim",
        "metadata",
        "created_at",
        "updated_at",
    }


async def test_listing_is_paginated(client: AsyncClient) -> None:
    for index in range(3):
        await _create(client, name=f"KB {index}")

    response = await client.get("/api/v1/knowledge-bases", params={"limit": 2, "offset": 0})

    assert response.status_code == 200
    body = response.json()
    assert len(body["items"]) == 2
    assert body["total"] >= 3
    assert body["limit"] == 2


async def test_delete_removes_the_knowledge_base(client: AsyncClient) -> None:
    created = await _create(client)

    assert (await client.delete(f"/api/v1/knowledge-bases/{created['id']}")).status_code == 204
    assert (await client.get(f"/api/v1/knowledge-bases/{created['id']}")).status_code == 404


async def test_stats_report_an_empty_knowledge_base_honestly(client: AsyncClient) -> None:
    created = await _create(client)

    stats = await client.get(f"/api/v1/knowledge-bases/{created['id']}/stats")

    assert stats.status_code == 200
    assert stats.json()["document_count"] == 0
    assert stats.json()["chunk_count"] == 0


async def test_pgvector_extension_and_hnsw_index_exist(session: AsyncSession) -> None:
    """The migration's real payload: a vector column that is actually indexed."""
    version = await session.scalar(
        text("SELECT extversion FROM pg_extension WHERE extname='vector'")
    )
    assert version is not None
    major, minor, *_ = (int(part) for part in str(version).split("."))
    # Iterative index scans, which filtered search depends on, need >= 0.8.
    assert (major, minor) >= (0, 8), f"pgvector {version} is too old"

    column_type = await session.scalar(
        text(
            "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
            "WHERE attrelid = 'document_chunks'::regclass AND attname = 'embedding'"
        )
    )
    assert column_type == "vector(768)"

    index_def = await session.scalar(
        text(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_document_chunks_embedding_hnsw'"
        )
    )
    assert index_def is not None
    assert "USING hnsw" in index_def
    assert "vector_cosine_ops" in index_def


async def test_tsv_is_a_stored_generated_column(session: AsyncSession) -> None:
    generated = await session.scalar(
        text(
            "SELECT attgenerated FROM pg_attribute "
            "WHERE attrelid = 'document_chunks'::regclass AND attname = 'tsv'"
        )
    )
    # 's' = stored. A non-generated column would mean nothing maintains it.
    # attgenerated is Postgres's internal "char" type, which asyncpg hands back
    # as bytes rather than str.
    assert generated in ("s", b"s")
