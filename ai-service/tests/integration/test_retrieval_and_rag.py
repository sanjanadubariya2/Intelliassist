"""pgvector retrieval, knowledge-base isolation, and a complete RAG query.

The isolation tests are the important ones. A second populated knowledge base
is always present as a negative control, because a leak between tenants is
exactly the failure that produces no error — only wrong answers.
"""

from __future__ import annotations

import uuid

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.repositories.chunk_repo import ChunkRepository
from app.services.embeddings.base import EmbeddingTask
from app.services.embeddings.fake import FakeEmbeddingProvider
from app.services.retrieval.bm25 import Bm25Retriever
from app.services.retrieval.dense import DenseRetriever
from app.services.retrieval.fusion import FusionRetriever
from tests.integration.test_ingestion import HANDBOOK, ingest, make_knowledge_base

pytestmark = pytest.mark.integration

OTHER_CORPUS = b"""# Kitchen

The espresso machine on the third floor is descaled every Friday afternoon.
Do not put metal containers in the microwave under any circumstances.
"""


async def two_knowledge_bases(session: AsyncSession) -> tuple[uuid.UUID, uuid.UUID]:
    """One populated with the handbook, one with entirely unrelated content."""
    primary = await make_knowledge_base(session, ref="org-primary")
    other = await make_knowledge_base(session, ref="org-other")
    await ingest(session, primary, HANDBOOK)
    await ingest(session, other, OTHER_CORPUS)
    return primary, other


# ------------------------------------------------------- dense retrieval ----


async def test_dense_retrieval_finds_semantically_related_chunks(
    session: AsyncSession,
) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    await ingest(session, knowledge_base_id, HANDBOOK)

    results = await DenseRetriever(
        chunk_repo=ChunkRepository(session), embeddings=FakeEmbeddingProvider()
    ).retrieve(query="annual leave entitlement", knowledge_base_id=knowledge_base_id, limit=5)

    assert results
    assert "leave" in results[0].chunk.text.lower()


async def test_dense_retrieval_never_crosses_knowledge_bases(
    session: AsyncSession,
) -> None:
    primary, other = await two_knowledge_bases(session)

    results = await DenseRetriever(
        chunk_repo=ChunkRepository(session), embeddings=FakeEmbeddingProvider()
    ).retrieve(query="espresso machine descaling", knowledge_base_id=primary, limit=20)

    # The espresso chunk exists — in the other knowledge base. It must not be
    # reachable from this one even though it is the best semantic match.
    assert all(result.chunk.knowledge_base_id == primary for result in results)
    assert all("espresso" not in result.chunk.text.lower() for result in results)
    assert other != primary


async def test_similarity_scores_are_ordered_and_bounded(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    await ingest(session, knowledge_base_id, HANDBOOK)

    results = await DenseRetriever(
        chunk_repo=ChunkRepository(session), embeddings=FakeEmbeddingProvider()
    ).retrieve(query="expenses receipts", knowledge_base_id=knowledge_base_id, limit=5)

    scores = [result.score for result in results]
    assert scores == sorted(scores, reverse=True)
    # Cosine similarity, converted from pgvector's distance.
    assert all(-1.01 <= score <= 1.01 for score in scores)


async def test_document_filter_restricts_retrieval(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    first = await ingest(session, knowledge_base_id, HANDBOOK)
    await ingest(session, knowledge_base_id, OTHER_CORPUS)

    results = await DenseRetriever(
        chunk_repo=ChunkRepository(session), embeddings=FakeEmbeddingProvider()
    ).retrieve(
        query="anything at all",
        knowledge_base_id=knowledge_base_id,
        limit=20,
        document_ids=[first],
    )

    assert results
    assert all(result.chunk.document_id == first for result in results)


async def test_vector_search_returns_nothing_for_an_empty_knowledge_base(
    session: AsyncSession,
) -> None:
    empty = await make_knowledge_base(session, ref="org-empty-kb")

    results = await DenseRetriever(
        chunk_repo=ChunkRepository(session), embeddings=FakeEmbeddingProvider()
    ).retrieve(query="anything", knowledge_base_id=empty, limit=5)

    assert results == []


# -------------------------------------------------------- sparse + fusion ---


async def test_bm25_matches_an_exact_identifier(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    await ingest(session, knowledge_base_id, HANDBOOK)

    results = await Bm25Retriever(chunk_repo=ChunkRepository(session)).retrieve(
        query="E4092", knowledge_base_id=knowledge_base_id, limit=5
    )

    assert results
    assert "E4092" in results[0].chunk.text


async def test_bm25_respects_knowledge_base_isolation(session: AsyncSession) -> None:
    primary, _ = await two_knowledge_bases(session)

    results = await Bm25Retriever(chunk_repo=ChunkRepository(session)).retrieve(
        query="espresso microwave", knowledge_base_id=primary, limit=10
    )

    assert all(result.chunk.knowledge_base_id == primary for result in results)


async def test_fusion_combines_both_channels(session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session)
    await ingest(session, knowledge_base_id, HANDBOOK)

    chunk_repo = ChunkRepository(session)
    fusion = FusionRetriever(
        retrievers=[
            DenseRetriever(chunk_repo=chunk_repo, embeddings=FakeEmbeddingProvider()),
            Bm25Retriever(chunk_repo=chunk_repo),
        ]
    )

    results = await fusion.retrieve(
        query="reimbursement for travel receipts",
        knowledge_base_id=knowledge_base_id,
        limit=5,
    )

    assert results
    assert results[0].score > 0
    assert len({result.chunk.chunk_id for result in results}) == len(results)


async def test_postgres_full_text_path_also_isolates(session: AsyncSession) -> None:
    # The documented scale path for BM25 must honour the same boundary.
    primary, _ = await two_knowledge_bases(session)

    results = await ChunkRepository(session).search_by_text(
        knowledge_base_id=primary, query="espresso", limit=10
    )

    assert all(result.chunk.knowledge_base_id == primary for result in results)


async def test_query_and_document_embeddings_use_different_task_types() -> None:
    provider = FakeEmbeddingProvider()

    as_document = await provider.embed_one("text", task=EmbeddingTask.DOCUMENT)
    as_query = await provider.embed_one("text", task=EmbeddingTask.QUERY)

    # The fake ignores task type; this pins the call signature the real
    # provider depends on so a refactor cannot quietly drop it.
    assert len(as_document) == len(as_query) == 768


# --------------------------------------------------------- full RAG query ---


async def test_complete_rag_query_returns_a_grounded_answer(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-rag")
    await ingest(session, knowledge_base_id, HANDBOOK)
    await session.commit()

    response = await client.post(
        "/api/v1/rag/query",
        json={"knowledge_base_id": str(knowledge_base_id), "query": "How much annual leave?"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["grounded"] is True
    assert body["answer"]
    assert body["citations"]
    assert body["timings"]["total_ms"] >= 0
    assert body["usage"]["context_tokens"] > 0


async def test_citations_point_at_real_retrievable_chunks(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-cite")
    await ingest(session, knowledge_base_id, HANDBOOK)
    await session.commit()

    body = (
        await client.post(
            "/api/v1/rag/query",
            json={"knowledge_base_id": str(knowledge_base_id), "query": "expenses receipts"},
        )
    ).json()

    cited = [uuid.UUID(citation["chunk_id"]) for citation in body["citations"]]
    hydrated = await ChunkRepository(session).get_by_ids(
        knowledge_base_id=knowledge_base_id, chunk_ids=cited
    )

    # Every citation must resolve to a chunk that actually exists.
    assert len(hydrated) == len(cited)


async def test_query_against_an_empty_knowledge_base_abstains(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-abstain")
    await session.commit()

    body = (
        await client.post(
            "/api/v1/rag/query",
            json={"knowledge_base_id": str(knowledge_base_id), "query": "anything"},
        )
    ).json()

    assert body["grounded"] is False
    assert body["citations"] == []
    assert "don't have information" in body["answer"]


async def test_query_against_a_missing_knowledge_base_is_404(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/rag/query",
        json={"knowledge_base_id": str(uuid.uuid4()), "query": "anything"},
    )

    assert response.status_code == 404


async def test_query_validation_rejects_an_empty_question(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-validate")
    await session.commit()

    response = await client.post(
        "/api/v1/rag/query",
        json={"knowledge_base_id": str(knowledge_base_id), "query": ""},
    )

    assert response.status_code == 422


# ------------------------------------------------------------- streaming ----


async def test_stream_emits_sse_events_in_order(client: AsyncClient, session: AsyncSession) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-stream")
    await ingest(session, knowledge_base_id, HANDBOOK)
    await session.commit()

    async with client.stream(
        "POST",
        "/api/v1/rag/stream",
        json={"knowledge_base_id": str(knowledge_base_id), "query": "annual leave"},
    ) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        body = "".join([chunk async for chunk in response.aiter_text()])

    events = [line[len("event: ") :] for line in body.splitlines() if line.startswith("event: ")]

    assert events[0] == "metadata"
    assert events[-1] == "done"
    assert "token" in events


async def test_stream_sends_sources_before_any_token(
    client: AsyncClient, session: AsyncSession
) -> None:
    knowledge_base_id = await make_knowledge_base(session, ref="org-stream-src")
    await ingest(session, knowledge_base_id, HANDBOOK)
    await session.commit()

    async with client.stream(
        "POST",
        "/api/v1/rag/stream",
        json={"knowledge_base_id": str(knowledge_base_id), "query": "expenses"},
    ) as response:
        body = "".join([chunk async for chunk in response.aiter_text()])

    metadata_position = body.index("event: metadata")
    token_position = body.index("event: token")

    # A UI can render provenance while the answer is still arriving.
    assert metadata_position < token_position
    assert '"sources"' in body
